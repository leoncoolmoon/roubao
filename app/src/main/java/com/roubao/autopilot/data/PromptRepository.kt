package com.roubao.autopilot.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.roubao.autopilot.agent.AccessLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** 读取可能尚未迁移的列，避免旧 DB 直接崩。 */
private fun Cursor.getStringOr(name: String, default: String): String {
    val index = getColumnIndex(name)
    return if (index >= 0) getString(index) ?: default else default
}

data class AgentPromptEntity(
    val id: String,
    val name: String,
    val icon: String,
    val role: String,
    val description: String,
    val responsibilities: List<String>,
    val prompt: String,
    val defaultPrompt: String,
    val isDefault: Boolean,
    val enabled: Boolean = true
)

data class ToolSkillPromptEntity(
    val id: String,
    val name: String,
    val displayName: String,
    val category: String, // "tool" or "skill"
    val description: String,
    val prompt: String,
    val defaultPrompt: String,
    val isDefault: Boolean,
    val enabled: Boolean = true,
    val keywordsJson: String = "[]",
    val paramsJson: String = "[]",
    val relatedAppsJson: String = "[]",
    val promptHint: String = "",
    val domain: String = "",
    val source: String = "custom",
    val executable: Boolean = false,
    val dependenceJson: String = "[]"
)

data class UserFeedbackEntity(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val enabled: Boolean,
    val kind: String = "complaint", // "suggestion" or "complaint"
    val dependenceJson: String = "[]"
)

data class PromptPackEntity(
    val id: String,
    val displayName: String,
    val level: String,
    val description: String,
    val sortOrder: Int = 0,
    val isBuiltin: Boolean = true
)

data class PromptImportResult(
    val agents: Int,
    val tools: Int,
    val feedback: Int,
    val errors: List<String>
)

/** 归一化反馈文本：仅用于判重，不改变落库原文。 */
private fun normalizeFeedbackText(text: String): String =
    text.trim()
        .replace(Regex("\\s+"), " ")
        .trim { it.isWhitespace() || it in "。！？!?.;；,，" }
        .lowercase(Locale.ROOT)

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * 上一版内置/预设 Agent Prompt 的指纹。
 * 只有命中这些指纹才在 v5 迁移时替换，避免覆盖真正手改过的 Prompt。
 */
private val LEGACY_AGENT_PROMPT_HASHES = mapOf(
    "manager" to setOf(
        "2bbdec26b012c32c5e5788fb926d7d169a80b97d15b947a4f69f090432d6ac46",
        "f22cc9cd39d2e7352bbdc8dff36d2c5535784f9d8e69fd72942143b5377c2083"
    ),
    "executor" to setOf(
        "73a77a0c33fddcf8bdab9d3f4ae9d75824c429fbfc8a42f1dd96e756f1336a59",
        "99642e0f4e4f69a6899b7614974ef94080d04dc173d96460180410e8a73eb732"
    ),
    "reflector" to setOf(
        "5d89e1b1e6514cabf0e236c378ce646d988c55bc6d9e85a363bcdc2330124b96",
        "3262c2439cda07e3dba84e53721ea446f9db3ad462afc037fd04726c98f7d778"
    ),
    "notetaker" to setOf(
        "8cf2e0fbd3e94b68ab81cd68504f54bb1854624e445940843fa3c9434b07a941",
        "f43a88e076cab7d3d12bb4cbe853dff61cb0c2b48c9b6e488f7fa325374943af"
    )
)

/** 去重并重写后的预设反馈：一条只覆盖一个操作原则。 */
private val LEGACY_PRESET_FEEDBACK = listOf(
    "suggestion" to "列表翻页优先使用 scroll，避免重复 swipe。",
    "suggestion" to "元素定位优先使用无障碍 UI 树，避免依赖截图坐标。",
    "suggestion" to "截图仅用于 UI 树无法覆盖的视觉判断。",
    "complaint" to "文件与系统操作优先使用 shell，不要打开文件管理器。",
    "complaint" to "文本输入使用 type（ACTION_SET_TEXT），不要使用屏幕键盘。",
    "complaint" to "光标与选区使用 set_selection，不要长按拖拽。",
    "complaint" to "同一操作连续失败两次后必须更换策略，不要重复点击同一位置。"
)

/** v10：把旧版随手写的反馈替换成专业、单职责的表述。 */
private val LEGACY_FEEDBACK_REWRITES = listOf(
    "翻页优先用 scroll，不要反复 swipe。" to "列表翻页优先使用 scroll，避免重复 swipe。",
    "能用无障碍 UI 树文本定位，就不要靠截图猜坐标。" to "元素定位优先使用无障碍 UI 树，避免依赖截图坐标。",
    "截图只在需要视觉判断时使用，不要每一步都依赖截图。" to "截图仅用于 UI 树无法覆盖的视觉判断。",
    "不要打开文件管理器做文件操作，任务会因此失败。" to "文件与系统操作优先使用 shell，不要打开文件管理器。",
    "不要点键盘中/英切输入法，type 会直接写入。" to "文本输入使用 type（ACTION_SET_TEXT），不要使用屏幕键盘。",
    "不要长按拖拽选字，用 set_selection。" to "光标与选区使用 set_selection，不要长按拖拽。",
    "不要反复点同一个位置；失败两次必须换策略。" to "同一操作连续失败两次后必须更换策略，不要重复点击同一位置。"
).map { normalizeFeedbackText(it.first) to it.second }

/** v7 清理：上一版原样恢复时带回来的 3 条重复建议。 */
private val LEGACY_DUPLICATE_SUGGESTIONS = setOf(
    "文件/系统任务优先用 shell（ls/find/grep/cp/mv/mkdir/rm/stat），不要打开文件管理器 UI。",
    "输入文字优先用 type（ACTION_SET_TEXT），不要点键盘中/英。",
    "选择文字/移动光标优先用 set_selection，不要长按拖拽。"
).map { normalizeFeedbackText(it) }.toSet()

class PromptRepository private constructor(context: Context) {

    private val dbHelper = PromptOpenHelper(context.applicationContext)

    fun getAllAgentPrompts(): List<AgentPromptEntity> {
        val list = mutableListOf<AgentPromptEntity>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "agent_prompts",
            null, null, null, null, null,
            "is_default DESC, id ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val respJson = it.getString(it.getColumnIndexOrThrow("responsibilities"))
                val respList = try {
                    val array = JSONArray(respJson)
                    List(array.length()) { i -> array.getString(i) }
                } catch (e: Exception) {
                    emptyList()
                }
                list.add(
                    AgentPromptEntity(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        icon = it.getString(it.getColumnIndexOrThrow("icon")),
                        role = it.getString(it.getColumnIndexOrThrow("role")),
                        description = it.getString(it.getColumnIndexOrThrow("description")),
                        responsibilities = respList,
                        prompt = it.getString(it.getColumnIndexOrThrow("prompt")),
                        defaultPrompt = it.getString(it.getColumnIndexOrThrow("default_prompt")),
                        isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1,
                        enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1
                    )
                )
            }
        }
        return list
    }

    fun getAgentPrompt(id: String): AgentPromptEntity? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "agent_prompts",
            null, "id = ?", arrayOf(id), null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val respJson = it.getString(it.getColumnIndexOrThrow("responsibilities"))
                val respList = try {
                    val array = JSONArray(respJson)
                    List(array.length()) { i -> array.getString(i) }
                } catch (e: Exception) {
                    emptyList()
                }
                return AgentPromptEntity(
                    id = it.getString(it.getColumnIndexOrThrow("id")),
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    icon = it.getString(it.getColumnIndexOrThrow("icon")),
                    role = it.getString(it.getColumnIndexOrThrow("role")),
                    description = it.getString(it.getColumnIndexOrThrow("description")),
                    responsibilities = respList,
                    prompt = it.getString(it.getColumnIndexOrThrow("prompt")),
                    defaultPrompt = it.getString(it.getColumnIndexOrThrow("default_prompt")),
                    isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1,
                    enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1
                )
            }
        }
        return null
    }

    /**
     * 按 pack 解析 Agent prompt：base + overlay（replace/append/prepend）。
     * 没有 overlay 时直接用 base。
     */
    fun getAgentPrompt(id: String, packId: String): AgentPromptEntity? {
        val base = getAgentPrompt(id) ?: return null
        if (packId.isBlank()) return base

        val db = dbHelper.readableDatabase
        var mode = ""
        var overlay = ""
        db.query(
            "agent_pack_overlays",
            arrayOf("mode", "prompt"),
            "agent_id = ? AND pack_id = ?",
            arrayOf(id, packId),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                mode = cursor.getString(0) ?: ""
                overlay = cursor.getString(1) ?: ""
            }
        }
        if (overlay.isBlank()) return base

        val resolved = when (mode) {
            "replace" -> overlay
            "prepend" -> overlay.trimEnd() + "\n\n" + base.prompt
            else -> base.prompt.trimEnd() + "\n\n" + overlay
        }
        return base.copy(prompt = resolved)
    }

    fun getAllPacks(): List<PromptPackEntity> {
        val list = mutableListOf<PromptPackEntity>()
        dbHelper.readableDatabase.query(
            "prompt_packs",
            null, null, null, null, null,
            "sort_order ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    PromptPackEntity(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                        level = cursor.getString(cursor.getColumnIndexOrThrow("level")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
                        isBuiltin = cursor.getInt(cursor.getColumnIndexOrThrow("is_builtin")) == 1
                    )
                )
            }
        }
        return list
    }

    fun getPack(id: String): PromptPackEntity? =
        getAllPacks().firstOrNull { it.id == id }

    fun getToolSkillPromptsForLevel(level: AccessLevel): List<ToolSkillPromptEntity> =
        getAllToolSkillPrompts().filter {
            it.enabled && AccessLevel.satisfies(level, it.dependenceJson)
        }

    fun getSkillPromptsForLevel(level: AccessLevel): List<ToolSkillPromptEntity> =
        getToolSkillPromptsForLevel(level).filter { it.category == "skill" && it.prompt.isNotBlank() }

    fun updateAgentPrompt(id: String, newPrompt: String) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("prompt", newPrompt)
        }
        db.update("agent_prompts", cv, "id = ?", arrayOf(id))
    }

    /** 同时更新 Agent 的展示信息和 Prompt（添加/编辑共用表单）。 */
    fun updateAgentDetails(agent: AgentPromptEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("name", agent.name)
            put("icon", agent.icon)
            put("role", agent.role)
            put("description", agent.description)
            put("responsibilities", JSONArray(agent.responsibilities).toString())
            put("prompt", agent.prompt)
        }
        db.update("agent_prompts", cv, "id = ?", arrayOf(agent.id))
    }

    fun updateAgentEnabled(id: String, enabled: Boolean) {
        val db = dbHelper.writableDatabase
        db.update(
            "agent_prompts",
            ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "id = ?",
            arrayOf(id)
        )
    }

    fun isAgentEnabled(id: String): Boolean = getAgentPrompt(id)?.enabled ?: true

    fun resetAgentPromptToDefault(id: String) {
        val db = dbHelper.writableDatabase
        val agent = getAgentPrompt(id) ?: return
        val cv = ContentValues().apply {
            put("prompt", agent.defaultPrompt)
        }
        db.update("agent_prompts", cv, "id = ?", arrayOf(id))
    }

    fun addAgentPrompt(agent: AgentPromptEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("id", agent.id)
            put("name", agent.name)
            put("icon", agent.icon)
            put("role", agent.role)
            put("description", agent.description)
            put("responsibilities", JSONArray(agent.responsibilities).toString())
            put("prompt", agent.prompt)
            put("default_prompt", agent.defaultPrompt)
            put("is_default", if (agent.isDefault) 1 else 0)
            put("enabled", if (agent.enabled) 1 else 0)
        }
        db.insertWithOnConflict("agent_prompts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteAgentPrompt(id: String): Boolean {
        val agent = getAgentPrompt(id) ?: return false
        if (agent.isDefault) return false
        val db = dbHelper.writableDatabase
        return db.delete("agent_prompts", "id = ?", arrayOf(id)) > 0
    }

    fun getAllToolSkillPrompts(): List<ToolSkillPromptEntity> {
        val list = mutableListOf<ToolSkillPromptEntity>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "tool_skill_prompts",
            null, null, null, null, null,
            "is_default DESC, category ASC, id ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ToolSkillPromptEntity(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        displayName = it.getString(it.getColumnIndexOrThrow("display_name")),
                        category = it.getString(it.getColumnIndexOrThrow("category")),
                        description = it.getString(it.getColumnIndexOrThrow("description")),
                        prompt = it.getString(it.getColumnIndexOrThrow("prompt")),
                        defaultPrompt = it.getString(it.getColumnIndexOrThrow("default_prompt")),
                        isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1,
                        enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1,
                        keywordsJson = it.getString(it.getColumnIndexOrThrow("keywords_json")),
                        paramsJson = it.getString(it.getColumnIndexOrThrow("params_json")),
                        relatedAppsJson = it.getString(it.getColumnIndexOrThrow("related_apps_json")),
                        promptHint = it.getString(it.getColumnIndexOrThrow("prompt_hint")),
                        domain = it.getString(it.getColumnIndexOrThrow("domain")),
                        source = it.getString(it.getColumnIndexOrThrow("source")),
                        executable = it.getInt(it.getColumnIndexOrThrow("executable")) == 1,
                        dependenceJson = it.getStringOr("dependence_json", "[]")
                    )
                )
            }
        }
        return list
    }

    fun getToolSkillPrompt(idOrName: String): ToolSkillPromptEntity? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "tool_skill_prompts",
            null, "id = ? OR name = ?", arrayOf(idOrName, idOrName), null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return ToolSkillPromptEntity(
                    id = it.getString(it.getColumnIndexOrThrow("id")),
                    name = it.getString(it.getColumnIndexOrThrow("name")),
                    displayName = it.getString(it.getColumnIndexOrThrow("display_name")),
                    category = it.getString(it.getColumnIndexOrThrow("category")),
                    description = it.getString(it.getColumnIndexOrThrow("description")),
                    prompt = it.getString(it.getColumnIndexOrThrow("prompt")),
                    defaultPrompt = it.getString(it.getColumnIndexOrThrow("default_prompt")),
                    isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1,
                    enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1,
                    keywordsJson = it.getString(it.getColumnIndexOrThrow("keywords_json")),
                    paramsJson = it.getString(it.getColumnIndexOrThrow("params_json")),
                    relatedAppsJson = it.getString(it.getColumnIndexOrThrow("related_apps_json")),
                    promptHint = it.getString(it.getColumnIndexOrThrow("prompt_hint")),
                    domain = it.getString(it.getColumnIndexOrThrow("domain")),
                    source = it.getString(it.getColumnIndexOrThrow("source")),
                    executable = it.getInt(it.getColumnIndexOrThrow("executable")) == 1,
                    dependenceJson = it.getStringOr("dependence_json", "[]")
                )
            }
        }
        return null
    }

    fun updateToolSkillPrompt(id: String, newPrompt: String) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("prompt", newPrompt)
        }
        db.update("tool_skill_prompts", cv, "id = ?", arrayOf(id))
    }

    /** 同时更新 Tool/Skill 的展示信息和 Prompt（添加/编辑共用表单）。 */
    fun updateToolSkillDetails(item: ToolSkillPromptEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("display_name", item.displayName)
            put("description", item.description)
            put("prompt", item.prompt)
            put("keywords_json", item.keywordsJson)
            put("params_json", item.paramsJson)
            put("related_apps_json", item.relatedAppsJson)
            put("prompt_hint", item.promptHint)
            put("domain", item.domain)
            put("source", item.source)
            put("executable", if (item.executable) 1 else 0)
            put("dependence_json", item.dependenceJson)
        }
        db.update("tool_skill_prompts", cv, "id = ?", arrayOf(item.id))
    }

    fun updateToolSkillEnabled(id: String, enabled: Boolean) {
        val db = dbHelper.writableDatabase
        db.update(
            "tool_skill_prompts",
            ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "id = ?",
            arrayOf(id)
        )
    }

    fun isToolSkillEnabled(idOrName: String): Boolean =
        getToolSkillPrompt(idOrName)?.enabled ?: true

    fun resetToolSkillPromptToDefault(id: String) {
        val db = dbHelper.writableDatabase
        val item = getToolSkillPrompt(id) ?: return
        val cv = ContentValues().apply {
            put("prompt", item.defaultPrompt)
        }
        db.update("tool_skill_prompts", cv, "id = ?", arrayOf(id))
    }

    fun addToolSkillPrompt(item: ToolSkillPromptEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("id", item.id)
            put("name", item.name)
            put("display_name", item.displayName)
            put("category", item.category)
            put("description", item.description)
            put("prompt", item.prompt)
            put("default_prompt", item.defaultPrompt)
            put("is_default", if (item.isDefault) 1 else 0)
            put("enabled", if (item.enabled) 1 else 0)
            put("keywords_json", item.keywordsJson)
            put("params_json", item.paramsJson)
            put("related_apps_json", item.relatedAppsJson)
            put("prompt_hint", item.promptHint)
            put("domain", item.domain)
            put("source", item.source)
            put("executable", if (item.executable) 1 else 0)
            put("dependence_json", item.dependenceJson)
        }
        db.insertWithOnConflict("tool_skill_prompts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteToolSkillPrompt(id: String): Boolean {
        val item = getToolSkillPrompt(id) ?: return false
        if (item.isDefault) return false
        val db = dbHelper.writableDatabase
        return db.delete("tool_skill_prompts", "id = ?", arrayOf(id)) > 0
    }

    // ---------------------------------------------------------------------
    // 用户负面反馈 / 教训（会注入 Manager / Executor prompt）
    // ---------------------------------------------------------------------

    fun getAllFeedback(): List<UserFeedbackEntity> {
        val list = mutableListOf<UserFeedbackEntity>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "user_feedback",
            null, null, null, null, null,
            "created_at DESC, id DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    UserFeedbackEntity(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        text = it.getString(it.getColumnIndexOrThrow("text")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                        enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1,
                        kind = it.getString(it.getColumnIndexOrThrow("kind")),
                        dependenceJson = it.getStringOr("dependence_json", "[]")
                    )
                )
            }
        }
        return list
    }

    fun addFeedback(text: String, kind: String = "complaint"): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return -1
        val safeKind = if (kind == "suggestion") "suggestion" else "complaint"
        val db = dbHelper.writableDatabase
        if (findFeedbackByNormalizedText(db, normalizeFeedbackText(trimmed)) != null) {
            return FEEDBACK_DUPLICATE
        }
        val cv = ContentValues().apply {
            put("text", trimmed)
            put("created_at", System.currentTimeMillis())
            put("enabled", 1)
            put("kind", safeKind)
        }
        return db.insert("user_feedback", null, cv)
    }

    private fun findFeedbackByNormalizedText(db: SQLiteDatabase, normalized: String): Long? {
        val cursor = db.query(
            "user_feedback",
            arrayOf("id", "text"),
            null, null, null, null,
            "created_at DESC, id DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                if (normalizeFeedbackText(it.getString(1)) == normalized) {
                    return it.getLong(0)
                }
            }
        }
        return null
    }

    fun updateFeedback(id: Long, text: String, kind: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val safeKind = if (kind == "suggestion") "suggestion" else "complaint"
        val db = dbHelper.writableDatabase
        val duplicateId = findFeedbackByNormalizedText(db, normalizeFeedbackText(trimmed))
        if (duplicateId != null && duplicateId != id) return false
        db.update(
            "user_feedback",
            ContentValues().apply {
                put("text", trimmed)
                put("kind", safeKind)
            },
            "id = ?",
            arrayOf(id.toString())
        )
        return true
    }

    fun setFeedbackEnabled(id: Long, enabled: Boolean) {
        val db = dbHelper.writableDatabase
        db.update(
            "user_feedback",
            ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun deleteFeedback(id: Long): Boolean {
        val db = dbHelper.writableDatabase
        return db.delete("user_feedback", "id = ?", arrayOf(id.toString())) > 0
    }

    /** 返回可直接注入 prompt 的反馈文本；没有启用项时返回空串。 */
    fun getFeedbackPrompt(level: AccessLevel = AccessLevel.USER): String {
        return try {
            val active = getAllFeedback()
                .filter {
                    it.enabled && it.text.isNotBlank() &&
                        AccessLevel.satisfies(level, it.dependenceJson)
                }
                .distinctBy { normalizeFeedbackText(it.text) }
                .take(30)
            val suggestions = active.filter { it.kind == "suggestion" }
            val complaints = active.filter { it.kind != "suggestion" }
            buildString {
                if (suggestions.isNotEmpty()) {
                    append("### User Suggestions (positive) ###\n")
                    suggestions.forEach { append("- ${it.text.trim()}\n") }
                }
                if (complaints.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("### User Complaints / Lessons (negative) ###\n")
                    complaints.forEach { append("- ${it.text.trim()}\n") }
                }
            }.trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ---------------------------------------------------------------------
    // 批量 JSON 导入 / 导出
    // ---------------------------------------------------------------------

    fun exportPromptsJson(): String {
        val root = JSONObject()
        root.put("format", "roubao-prompts")
        root.put("version", 2)
        root.put("exported_at", System.currentTimeMillis())

        val agents = JSONArray()
        getAllAgentPrompts().forEach { agent ->
            agents.put(agentToJson(agent))
        }
        root.put("agents", agents)

        val tools = JSONArray()
        getAllToolSkillPrompts().forEach { item ->
            tools.put(toolSkillToJson(item))
        }
        root.put("tools", tools)

        val feedback = JSONArray()
        getAllFeedback().forEach {
            feedback.put(JSONObject().apply {
                put("text", it.text)
                put("kind", it.kind)
            })
        }
        root.put("feedback", feedback)
        return root.toString(2)
    }

    private fun agentToJson(agent: AgentPromptEntity): JSONObject = JSONObject().apply {
        put("id", agent.id)
        put("name", agent.name)
        put("icon", agent.icon)
        put("role", agent.role)
        put("description", agent.description)
        put("responsibilities", JSONArray(agent.responsibilities))
        put("prompt", agent.prompt)
        put("default_prompt", agent.defaultPrompt)
        put("is_default", agent.isDefault)
        put("enabled", agent.enabled)
    }

    private fun toolSkillToJson(item: ToolSkillPromptEntity): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("name", item.name)
        put("display_name", item.displayName)
        put("category", item.category)
        put("description", item.description)
        put("prompt", item.prompt)
        put("default_prompt", item.defaultPrompt)
        put("is_default", item.isDefault)
        put("enabled", item.enabled)
        put("keywords", parseJsonArray(item.keywordsJson))
        put("params", parseJsonArray(item.paramsJson))
        put("related_apps", parseJsonArray(item.relatedAppsJson))
        put("prompt_hint", item.promptHint)
        put("domain", item.domain)
        put("source", item.source)
        put("executable", item.executable)
    }

    private fun parseJsonArray(value: String): JSONArray = try {
        JSONArray(value.ifBlank { "[]" })
    } catch (e: Exception) {
        JSONArray()
    }

    /** 单条 Agent 导出，格式与完整导出兼容。 */
    fun exportAgentJson(agent: AgentPromptEntity): String = JSONObject().apply {
        put("format", "roubao-prompts")
        put("version", 2)
        put("exported_at", System.currentTimeMillis())
        put("agents", JSONArray().put(agentToJson(agent)))
    }.toString(2)

    /** 单条 Tool/Skill 导出，格式与完整导出兼容。 */
    fun exportToolSkillJson(item: ToolSkillPromptEntity): String = JSONObject().apply {
        put("format", "roubao-prompts")
        put("version", 2)
        put("exported_at", System.currentTimeMillis())
        put("tools", JSONArray().put(toolSkillToJson(item)))
    }.toString(2)

    /** 单条导入：只接受与目标卡片类型匹配的 JSON。 */
    fun importSinglePromptJson(json: String, category: String): PromptImportResult {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return PromptImportResult(0, 0, 0, listOf("JSON 解析失败: ${e.message}"))
        }
        val agentsArr = root.optJSONArray("agents")
        val toolsArr = root.optJSONArray("tools")
        val hasAgents = agentsArr != null && agentsArr.length() > 0
        val hasTools = toolsArr != null && toolsArr.length() > 0

        val valid = when (category) {
            "agent" -> hasAgents && !hasTools
            "tool", "skill" -> {
                val firstCategory = toolsArr?.optJSONObject(0)?.optString("category")
                hasTools && !hasAgents && firstCategory == category
            }
            else -> false
        }
        if (!valid) {
            return PromptImportResult(0, 0, 0, listOf("文件类型与目标不匹配，未导入"))
        }
        return importPromptsJson(json)
    }

    fun importPromptsJson(json: String): PromptImportResult {
        val errors = mutableListOf<String>()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return PromptImportResult(0, 0, 0, listOf("JSON 解析失败: ${e.message}"))
        }

        if (root.optString("format") != "roubao-prompts") {
            errors.add("format 不是 roubao-prompts（仍尝试导入）")
        }

        var agentCount = 0
        var toolCount = 0
        var feedbackCount = 0
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (root.optBoolean("replace_feedback", false)) {
                db.delete("user_feedback", null, null)
            }

            val agentsArr = root.optJSONArray("agents")
            if (agentsArr != null) {
                for (i in 0 until agentsArr.length()) {
                    val obj = agentsArr.optJSONObject(i)
                    if (obj == null) {
                        errors.add("agents[$i] 不是对象")
                        continue
                    }
                    val id = obj.optString("id").trim()
                    if (id.isEmpty()) {
                        errors.add("agents[$i] 缺少 id")
                        continue
                    }
                    val prompt = obj.optString("prompt")
                    if (prompt.isBlank()) {
                        errors.add("agent $id 缺少 prompt")
                        continue
                    }
                    val responsibilities = when (val r = obj.opt("responsibilities")) {
                        is JSONArray -> r.toString()
                        is String -> r
                        else -> "[]"
                    }
                    val cv = ContentValues().apply {
                        put("id", id)
                        put("name", obj.optString("name", id))
                        put("icon", obj.optString("icon", "🤖"))
                        put("role", obj.optString("role", "自定义角色"))
                        put("description", obj.optString("description", ""))
                        put("responsibilities", responsibilities)
                        put("prompt", prompt)
                        put("default_prompt", obj.optString("default_prompt", prompt))
                        put("is_default", if (obj.optBoolean("is_default", false)) 1 else 0)
                        put("enabled", if (obj.optBoolean("enabled", true)) 1 else 0)
                    }
                    db.insertWithOnConflict("agent_prompts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    agentCount++
                }
            }

            val toolsArr = root.optJSONArray("tools")
            if (toolsArr != null) {
                for (i in 0 until toolsArr.length()) {
                    val obj = toolsArr.optJSONObject(i)
                    if (obj == null) {
                        errors.add("tools[$i] 不是对象")
                        continue
                    }
                    val id = obj.optString("id").trim()
                    if (id.isEmpty()) {
                        errors.add("tools[$i] 缺少 id")
                        continue
                    }
                    val rawCategory = obj.optString("category", "tool")
                    val category = if (rawCategory == "tool" || rawCategory == "skill") rawCategory else "skill"
                    val domain = obj.optString("domain", rawCategory.takeIf { it != "tool" && it != "skill" }.orEmpty())
                    val description = obj.optString("description", "")
                    val prompt = obj.optString("prompt").ifBlank {
                        if (category == "skill") "技能: $id\n说明: $description"
                        else "工具: $id\n说明: $description"
                    }
                    val cv = ContentValues().apply {
                        put("id", id)
                        put("name", obj.optString("name", id))
                        put("display_name", obj.optString("display_name", obj.optString("name", id)))
                        put("category", category)
                        put("description", description)
                        put("prompt", prompt)
                        put("default_prompt", obj.optString("default_prompt", prompt))
                        put("is_default", if (obj.optBoolean("is_default", false)) 1 else 0)
                        put("enabled", if (obj.optBoolean("enabled", true)) 1 else 0)
                        put("keywords_json", obj.optJSONArray("keywords")?.toString() ?: "[]")
                        put("params_json", obj.optJSONArray("params")?.toString() ?: "[]")
                        put("related_apps_json", obj.optJSONArray("related_apps")?.toString() ?: "[]")
                        put("prompt_hint", obj.optString("prompt_hint", ""))
                        put("domain", domain)
                        put("source", obj.optString("source", "custom"))
                        put("executable", if (obj.optBoolean("executable", false)) 1 else 0)
                    }
                    db.insertWithOnConflict("tool_skill_prompts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    toolCount++
                }
            }

            val feedbackArr = root.optJSONArray("feedback")
            if (feedbackArr != null) {
                for (i in 0 until feedbackArr.length()) {
                    var kind = "complaint"
                    val text = when (val value = feedbackArr.opt(i)) {
                        is String -> value
                        is JSONObject -> {
                            kind = if (value.optString("kind") == "suggestion") "suggestion" else "complaint"
                            value.optString("text")
                        }
                        else -> ""
                    }.trim()
                    if (text.isEmpty()) continue
                    val exists = findFeedbackByNormalizedText(db, normalizeFeedbackText(text)) != null
                    if (!exists) {
                        db.insert("user_feedback", null, ContentValues().apply {
                            put("text", text)
                            put("created_at", System.currentTimeMillis())
                            put("enabled", 1)
                            put("kind", kind)
                        })
                        feedbackCount++
                    }
                }
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            errors.add("导入失败: ${e.message}")
        } finally {
            db.endTransaction()
        }

        return PromptImportResult(agentCount, toolCount, feedbackCount, errors)
    }

    private class PromptOpenHelper(context: Context) : SQLiteOpenHelper(context, "prompts.db", null, 16) {

        private val appContext = context.applicationContext

        init {
            copySeedIfMissing()
        }

        /**
         * 首次安装：优先复制 assets/prompts_seed.db（完整种子库）。
         * 已有用户 DB 时不覆盖；复制失败则回退到 onCreate 的代码 seed。
         */
        private fun copySeedIfMissing() {
            val dbFile = appContext.getDatabasePath("prompts.db")
            if (dbFile.exists() && dbFile.length() > 0) return
            val parent = dbFile.parentFile ?: return
            parent.mkdirs()
            val tmp = File(parent, "prompts_seed_tmp.db")
            try {
                appContext.assets.open("prompts_seed.db").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                val check = SQLiteDatabase.openDatabase(
                    tmp.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )
                val version = check.version
                check.close()
                if (version < 16) {
                    println("[PromptRepository] seed db version too old: $version")
                    tmp.delete()
                    return
                }
                if (dbFile.exists()) dbFile.delete()
                val moved = tmp.renameTo(dbFile)
                if (!moved) {
                    tmp.copyTo(dbFile, overwrite = true)
                    tmp.delete()
                }
                println("[PromptRepository] copied seed db v$version")
            } catch (e: Exception) {
                println("[PromptRepository] seed copy failed: ${e.message}")
                tmp.delete()
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE agent_prompts (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL,
                    role TEXT NOT NULL,
                    description TEXT NOT NULL,
                    responsibilities TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    default_prompt TEXT NOT NULL,
                    is_default INTEGER NOT NULL DEFAULT 1,
                    enabled INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE tool_skill_prompts (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    description TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    default_prompt TEXT NOT NULL,
                    is_default INTEGER NOT NULL DEFAULT 1,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    keywords_json TEXT NOT NULL DEFAULT '[]',
                    params_json TEXT NOT NULL DEFAULT '[]',
                    related_apps_json TEXT NOT NULL DEFAULT '[]',
                    prompt_hint TEXT NOT NULL DEFAULT '',
                    domain TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT 'custom',
                    executable INTEGER NOT NULL DEFAULT 0,
                    dependence_json TEXT NOT NULL DEFAULT '[]'
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_feedback (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    text TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    kind TEXT NOT NULL DEFAULT 'complaint',
                    dependence_json TEXT NOT NULL DEFAULT '[]'
                )
                """.trimIndent()
            )

            insertDefaultAgents(db)
            insertDefaultToolsAndSkills(db)
            seedBuiltInSkillsFromAssets(db)
            insertPresetFeedback(db)
            seedPacksV13(db)
            applyDependenceV13(db)
            enableBuiltinToolsV13(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 保留用户已经改过的 prompt，只补新表/新列。
            if (oldVersion < 3) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_feedback (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        text TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        kind TEXT NOT NULL DEFAULT 'complaint'
                    )
                    """.trimIndent()
                )
            }
            if (oldVersion < 4) {
                addColumnIfMissing(db, "agent_prompts", "enabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "tool_skill_prompts", "enabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "user_feedback", "kind", "TEXT NOT NULL DEFAULT 'complaint'")
            }
            if (oldVersion < 5) {
                migrateBuiltInAgentPrompts(db)
                dedupeFeedback(db)
            }
            if (oldVersion < 6) {
                insertPresetFeedback(db)
            }
            if (oldVersion < 7) {
                removeLegacyDuplicateSuggestions(db)
            }
            if (oldVersion < 8) {
                insertShellCalcSkill(db)
            }
            if (oldVersion < 9) {
                addColumnIfMissing(db, "tool_skill_prompts", "keywords_json", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfMissing(db, "tool_skill_prompts", "params_json", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfMissing(db, "tool_skill_prompts", "related_apps_json", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfMissing(db, "tool_skill_prompts", "prompt_hint", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "tool_skill_prompts", "domain", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "tool_skill_prompts", "source", "TEXT NOT NULL DEFAULT 'custom'")
                addColumnIfMissing(db, "tool_skill_prompts", "executable", "INTEGER NOT NULL DEFAULT 0")
                seedBuiltInSkillsFromAssets(db)
            }
            if (oldVersion < 10) {
                rewritePresetFeedback(db)
            }
            if (oldVersion < 11) {
                refreshToolAgnosticPrompts(db)
            }
            if (oldVersion < 12) {
                refreshToolAgnosticPrompts(db)
            }
            if (oldVersion < 13) {
                addColumnIfMissing(db, "tool_skill_prompts", "dependence_json", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfMissing(db, "user_feedback", "dependence_json", "TEXT NOT NULL DEFAULT '[]'")
                seedPacksV13(db)
                applyDependenceV13(db)
                enableBuiltinToolsV13(db)
            }
            if (oldVersion < 14) {
                // open_app/press_key 目前仍走 shell；basic 必须隐藏，而不是混进提示词。
                db.execSQL(
                    "UPDATE tool_skill_prompts SET dependence_json = '[\"adb\"]' " +
                        "WHERE id IN ('open_app', 'press_key')"
                )
            }
            if (oldVersion < 16) {
                // basic prompt 清掉 open_app/key 的痕迹，只保留 UI 动作与 system_button。
                db.update(
                    "agent_pack_overlays",
                    ContentValues().apply { put("prompt", BASIC_MANAGER_PROMPT) },
                    "agent_id = ? AND pack_id = ?",
                    arrayOf("manager", "basic")
                )
                db.update(
                    "agent_pack_overlays",
                    ContentValues().apply { put("prompt", BASIC_EXECUTOR_PROMPT) },
                    "agent_id = ? AND pack_id = ?",
                    arrayOf("executor", "basic")
                )
            }
        }

        /** v11：移除内置 Prompt 中静态写死的 shell/open_app 推荐。 */
        private fun refreshToolAgnosticPrompts(db: SQLiteDatabase) {
            val defaults = mapOf(
                "manager" to PromptRepository.DEFAULT_MANAGER_PROMPT,
                "executor" to PromptRepository.DEFAULT_EXECUTOR_PROMPT,
                "reflector" to PromptRepository.DEFAULT_REFLECTOR_PROMPT
            )
            for ((id, newDefault) in defaults) {
                val row = db.query(
                    "agent_prompts",
                    arrayOf("prompt", "default_prompt"),
                    "id = ?",
                    arrayOf(id),
                    null, null, null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                } ?: continue
                if (row.first == row.second) {
                    db.update(
                        "agent_prompts",
                        ContentValues().apply {
                            put("prompt", newDefault)
                            put("default_prompt", newDefault)
                        },
                        "id = ?",
                        arrayOf(id)
                    )
                    println("[PromptRepository] refreshed tool-agnostic prompt: $id")
                }
            }
        }

        /** v13：pack 表 + 基础/高级 overlay + dependence。 */
        private fun seedPacksV13(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS prompt_packs (
                    id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    level TEXT NOT NULL,
                    description TEXT NOT NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    is_builtin INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS agent_pack_overlays (
                    agent_id TEXT NOT NULL,
                    pack_id TEXT NOT NULL,
                    mode TEXT NOT NULL DEFAULT 'append',
                    prompt TEXT NOT NULL,
                    PRIMARY KEY (agent_id, pack_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS action_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    task_id TEXT,
                    pack_id TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    provider_id TEXT,
                    level TEXT NOT NULL,
                    action_json TEXT NOT NULL,
                    risk_level TEXT NOT NULL,
                    confirmed INTEGER NOT NULL DEFAULT 0,
                    result TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

            val packs = listOf(
                Triple("basic", "基础包（仅无障碍）", "user"),
                Triple("standard", "标准包（Shizuku / su-user）", "adb"),
                Triple("advanced", "高级包（Root）", "root")
            )
            packs.forEachIndexed { index, (id, name, level) ->
                db.insertWithOnConflict(
                    "prompt_packs",
                    null,
                    ContentValues().apply {
                        put("id", id)
                        put("display_name", name)
                        put("level", level)
                        put("description", if (id == "basic") "只用无障碍：UI 树 + 截图 + 手势" else if (id == "standard") "可用 adb 级 shell，文件/系统操作优先 shell" else "可用 root 级工具；高风险动作需确认")
                        put("sort_order", (index + 1) * 10)
                        put("is_builtin", 1)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            val overlays = listOf(
                Triple("manager", "basic", BASIC_MANAGER_PROMPT),
                Triple("executor", "basic", BASIC_EXECUTOR_PROMPT),
                Triple("manager", "advanced", ADVANCED_OVERLAY),
                Triple("executor", "advanced", ADVANCED_OVERLAY)
            )
            overlays.forEach { (agentId, packId, prompt) ->
                db.insertWithOnConflict(
                    "agent_pack_overlays",
                    null,
                    ContentValues().apply {
                        put("agent_id", agentId)
                        put("pack_id", packId)
                        put("mode", if (packId == "basic") "replace" else "append")
                        put("prompt", prompt)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }

        private fun applyDependenceV13(db: SQLiteDatabase) {
            val adbIds = listOf("shell", "shell_calc", "open_app", "press_key")
            adbIds.forEach { id ->
                db.update(
                    "tool_skill_prompts",
                    ContentValues().apply { put("dependence_json", "[\"adb\"]") },
                    "id = ?",
                    arrayOf(id)
                )
            }
            // 其余条目默认无依赖；把新列显式写成 []，避免旧库 NULL/空串。
            db.execSQL("UPDATE tool_skill_prompts SET dependence_json = '[]' WHERE dependence_json IS NULL OR dependence_json = '' OR dependence_json = 'null'")

            val feedbackRows = mutableListOf<Pair<Long, String>>()
            db.query("user_feedback", arrayOf("id", "text"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    feedbackRows.add(cursor.getLong(0) to cursor.getString(1).orEmpty())
                }
            }
            feedbackRows.forEach { (id, text) ->
                val deps = if (text.contains("shell") || text.contains("文件")) "[\"adb\"]" else "[]"
                db.update(
                    "user_feedback",
                    ContentValues().apply { put("dependence_json", deps) },
                    "id = ?",
                    arrayOf(id.toString())
                )
            }
        }

        private fun enableBuiltinToolsV13(db: SQLiteDatabase) {
            // 手机上的测试状态可能把 type/open_app/shell 停用了；内置条目回到启用状态，
            // 避免新装/升级后 basic 连 UI 输入都没有。用户仍可在能力页手动停用。
            db.execSQL("UPDATE tool_skill_prompts SET enabled = 1 WHERE is_default = 1")
            val toolIds = listOf("tap", "swipe", "type", "press_key", "open_app", "shell", "screenshot", "search_apps", "deep_link", "clipboard", "http")
            toolIds.forEach { id ->
                db.update(
                    "tool_skill_prompts",
                    ContentValues().apply {
                        put("source", "builtin")
                        put("executable", 1)
                    },
                    "id = ?",
                    arrayOf(id)
                )
            }
        }

        private val BASIC_MANAGER_PROMPT = """You are the planning agent for an Android automation app running in BASIC mode: accessibility only, no shell, no root.

### User Request ###
{{instruction}}

### Planning Rules ###
1. Only plan UI actions: type, set_selection, scroll, click(text), click(coordinate), system_button(BACK/HOME), wait.
2. To launch an app, go Home and tap the app icon or use the app drawer/search; do not use shell commands.
3. shell/root are unavailable in BASIC. If the task requires file/system operations, use ask_user to tell the user to enable Shizuku for the standard pack; do not try shell.
4. Use the Accessibility UI Tree to find element text and center coordinates. The screenshot is for understanding page content; the tree is for precise targets.
5. Keep 3-6 subgoals. Update the plan only when the page changes or an action fails.
6. If the same action failed twice, switch strategy.
7. Use ask_user when required details are missing; use answer only for the final response.

{{phaseGuidance}}

### Output ###
### Thought ###
One or two sentences.
### Historical Operations ###
Completed subgoals (or "None yet").
### Plan ###
1. ...
2. ..."""

        private val BASIC_EXECUTOR_PROMPT = """You are the execution agent for an Android automation app running in BASIC mode: accessibility only, no shell, no root.
Choose exactly ONE next action. Use the Accessibility UI Tree for element text/coordinates; use the screenshot to understand the page.

### User Request ###
{{instruction}}

### Overall Plan ###
{{plan}}

### Current Subgoal ###
{{currentSubgoal}}

### Progress Status ###
{{progressStatus}}

### Action Priority ###
1. TEXT INPUT -> type: click the input, then type exact text via ACTION_SET_TEXT. Do not tap the on-screen keyboard.
2. TEXT EDITING -> set_selection: start/end indices on the focused input.
3. PAGE / LIST -> scroll.
4. BACK / HOME -> system_button (accessibility performGlobalAction).
5. APP LAUNCH -> go Home, tap the app icon, or open the app drawer/search and tap the app.
6. ELEMENT CLICK -> click with exact text from the UI tree; center coordinate as fallback.
7. VISUAL-ONLY -> click with absolute pixel coordinates.

### Hard Rules ###
- Never use shell or root actions in BASIC mode.
- Text > coordinates; UI tree > screenshot. If the tree has the target, use its text and center coordinate.
- If the same action failed twice, change strategy.
- Use ask_user when required details are missing; use answer only for the final response.
- Stay on the current subgoal.

### Latest Action History ###
{{actionHistory}}

### Output ###
### Thought ###
1. **Screen**: ...
2. **Target**: ...
3. **Decision**: ...
### Action ###
One valid JSON object, no markdown fences.
### Description ###
One short sentence."""

        private val ADVANCED_OVERLAY = """### Advanced Mode (Root) ###
Root-level tools may be available in this pack.
- Prefer read-only commands first; confirm destructive or system-wide changes.
- Never modify /system, boot, or security settings unless the user explicitly asks.
- If a root action fails, report the exact error instead of retrying blindly."""

        /** v10：更新旧预设反馈的措辞；缺失的用新文案补齐。 */
        private fun rewritePresetFeedback(db: SQLiteDatabase) {
            for ((oldNormalized, newText) in LEGACY_FEEDBACK_REWRITES) {
                var id: Long? = null
                db.query(
                    "user_feedback",
                    arrayOf("id", "text"),
                    null, null, null, null,
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (normalizeFeedbackText(cursor.getString(1)) == oldNormalized) {
                            id = cursor.getLong(0)
                            break
                        }
                    }
                }
                id?.let {
                    db.update(
                        "user_feedback",
                        ContentValues().apply { put("text", newText) },
                        "id = ?",
                        arrayOf(it.toString())
                    )
                }
            }
            insertPresetFeedback(db)
        }

        /** 将 assets/skills.json 的完整 Skill 定义同步进数据库。 */
        private fun seedBuiltInSkillsFromAssets(db: SQLiteDatabase) {
            val jsonText = try {
                appContext.assets.open("skills.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                println("[PromptRepository] 读取 skills.json 失败: ${e.message}")
                return
            }
            val array = try {
                JSONArray(jsonText)
            } catch (e: Exception) {
                println("[PromptRepository] 解析 skills.json 失败: ${e.message}")
                return
            }
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val displayName = obj.optString("name", id)
                val description = obj.optString("description", "")
                val domain = obj.optString("category", "")
                val keywords = obj.optJSONArray("keywords")?.toString() ?: "[]"
                val params = obj.optJSONArray("params")?.toString() ?: "[]"
                val relatedApps = obj.optJSONArray("related_apps")?.toString() ?: "[]"
                val promptHint = obj.optString("prompt_hint", "")
                val prompt = buildAssetSkillPrompt(id, description, promptHint, obj.optJSONArray("related_apps"))

                val exists = db.query(
                    "tool_skill_prompts",
                    arrayOf("id"),
                    "id = ?",
                    arrayOf(id),
                    null, null, null
                ).use { it.moveToFirst() }

                val values = ContentValues().apply {
                    put("name", id)
                    put("display_name", displayName)
                    put("category", "skill")
                    put("description", description)
                    put("prompt", prompt)
                    put("default_prompt", prompt)
                    put("is_default", 1)
                    put("enabled", 1)
                    put("keywords_json", keywords)
                    put("params_json", params)
                    put("related_apps_json", relatedApps)
                    put("prompt_hint", promptHint)
                    put("domain", domain)
                    put("source", "builtin")
                    put("executable", 1)
                }
                if (exists) {
                    db.update("tool_skill_prompts", values, "id = ?", arrayOf(id))
                } else {
                    values.put("id", id)
                    db.insert("tool_skill_prompts", null, values)
                }
            }
            println("[PromptRepository] seeded ${array.length()} built-in skills from assets")
        }

        private fun buildAssetSkillPrompt(
            id: String,
            description: String,
            promptHint: String,
            relatedApps: JSONArray?
        ): String = buildString {
            append("技能: ").append(id).append('\n')
            append("说明: ").append(description)
            if (promptHint.isNotBlank()) {
                append("\n约束: ").append(promptHint)
            }
            if (relatedApps != null && relatedApps.length() > 0) {
                append("\n关联应用:")
                for (i in 0 until relatedApps.length()) {
                    val app = relatedApps.optJSONObject(i) ?: continue
                    append("\n- ").append(app.optString("name", app.optString("package")))
                    val steps = app.optJSONArray("steps")
                    if (steps != null && steps.length() > 0) {
                        append(": ")
                        append((0 until steps.length()).joinToString(" -> ") { steps.optString(it) })
                    }
                }
            }
        }

        /** v8：加入 shell_calc 测试 Skill。 */
        private fun insertShellCalcSkill(db: SQLiteDatabase) {
            val exists = db.query(
                "tool_skill_prompts",
                arrayOf("id"),
                "id = ?",
                arrayOf("shell_calc"),
                null, null, null
            ).use { it.moveToFirst() }
            if (exists) return
            db.insert("tool_skill_prompts", null, ContentValues().apply {
                put("id", "shell_calc")
                put("name", "shell_calc")
                put("display_name", "Shell 计算")
                put("category", "skill")
                put("description", "算术计算必须用 shell 执行 expr，不允许模型自己心算")
                put("prompt", PromptRepository.DEFAULT_SKILL_SHELL_CALC)
                put("default_prompt", PromptRepository.DEFAULT_SKILL_SHELL_CALC)
                put("is_default", 1)
                put("enabled", 1)
                put("domain", "计算")
                put("source", "builtin")
                put("executable", 1)
            })
            println("[PromptRepository] inserted shell_calc skill")
        }

        /**
         * 只替换命中旧版内置/预设指纹的 Agent Prompt。
         * 真正手改过的 Prompt（指纹不匹配）保持原样。
         */
        private fun migrateBuiltInAgentPrompts(db: SQLiteDatabase) {
            val newDefaults = mapOf(
                "manager" to PromptRepository.DEFAULT_MANAGER_PROMPT,
                "executor" to PromptRepository.DEFAULT_EXECUTOR_PROMPT,
                "reflector" to PromptRepository.DEFAULT_REFLECTOR_PROMPT,
                "notetaker" to PromptRepository.DEFAULT_NOTETAKER_PROMPT
            )
            for ((id, newDefault) in newDefaults) {
                val row = db.query(
                    "agent_prompts",
                    arrayOf("prompt"),
                    "id = ?",
                    arrayOf(id),
                    null, null, null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: continue

                val legacyHashes = LEGACY_AGENT_PROMPT_HASHES[id].orEmpty()
                if (sha256(row) !in legacyHashes) continue

                db.update(
                    "agent_prompts",
                    ContentValues().apply {
                        put("prompt", newDefault)
                        put("default_prompt", newDefault)
                    },
                    "id = ?",
                    arrayOf(id)
                )
                println("[PromptRepository] migrated default prompt: $id")
            }
        }

        /** v5：只删除归一化后的完全重复反馈。 */
        private fun dedupeFeedback(db: SQLiteDatabase) {
            val seen = mutableSetOf<String>()
            val toDelete = mutableListOf<Long>()
            db.query(
                "user_feedback",
                arrayOf("id", "text"),
                null, null, null, null,
                "created_at DESC, id DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val normalized = normalizeFeedbackText(cursor.getString(1))
                    if (!seen.add(normalized)) {
                        toDelete.add(id)
                    }
                }
            }
            for (id in toDelete) {
                db.delete("user_feedback", "id = ?", arrayOf(id.toString()))
            }
            if (toDelete.isNotEmpty()) {
                println("[PromptRepository] removed ${toDelete.size} duplicate feedback rows")
            }
        }

        /** v6 / 新安装：恢复旧预设反馈，已存在的按归一化文本跳过。 */
        private fun insertPresetFeedback(db: SQLiteDatabase) {
            val now = System.currentTimeMillis()
            LEGACY_PRESET_FEEDBACK.forEachIndexed { index, (kind, text) ->
                val normalized = normalizeFeedbackText(text)
                val exists = db.query(
                    "user_feedback",
                    arrayOf("id", "text"),
                    null, null, null, null,
                    null
                ).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (normalizeFeedbackText(cursor.getString(1)) == normalized) {
                            found = true
                            break
                        }
                    }
                    found
                }
                if (!exists) {
                    db.insert("user_feedback", null, ContentValues().apply {
                        put("text", text)
                        put("created_at", now + index)
                        put("enabled", 1)
                        put("kind", kind)
                    })
                }
            }
        }

        /** v7：删除上一版原样恢复时带回来的 3 条重复建议。 */
        private fun removeLegacyDuplicateSuggestions(db: SQLiteDatabase) {
            db.query(
                "user_feedback",
                arrayOf("id", "text"),
                null, null, null, null,
                null
            ).use { cursor ->
                val toDelete = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    if (normalizeFeedbackText(cursor.getString(1)) in LEGACY_DUPLICATE_SUGGESTIONS) {
                        toDelete.add(cursor.getLong(0))
                    }
                }
                for (id in toDelete) {
                    db.delete("user_feedback", "id = ?", arrayOf(id.toString()))
                }
                if (toDelete.isNotEmpty()) {
                    println("[PromptRepository] removed ${toDelete.size} duplicate suggestion rows")
                }
            }
        }

        private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
            val exists = try {
                db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                            found = true
                            break
                        }
                    }
                    found
                }
            } catch (e: Exception) {
                false
            }
            if (!exists) {
                try {
                    db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
                } catch (e: Exception) {
                    println("[PromptRepository] add column $table.$column failed: ${e.message}")
                }
            }
        }

        private fun insertDefaultAgents(db: SQLiteDatabase) {
            val defaultAgents = listOf(
                AgentPromptEntity(
                    id = "manager",
                    name = "Manager",
                    icon = "🎯",
                    role = "规划者",
                    description = "负责理解用户意图，制定高层次的执行计划，并跟踪任务进度。",
                    responsibilities = listOf(
                        "分析用户请求，理解真实意图",
                        "将复杂任务分解为可执行的子目标",
                        "制定执行计划和步骤顺序",
                        "根据执行反馈动态调整计划"
                    ),
                    prompt = DEFAULT_MANAGER_PROMPT,
                    defaultPrompt = DEFAULT_MANAGER_PROMPT,
                    isDefault = true
                ),
                AgentPromptEntity(
                    id = "executor",
                    name = "Executor",
                    icon = "⚡",
                    role = "执行者",
                    description = "负责分析当前屏幕状态，决定具体的操作动作。",
                    responsibilities = listOf(
                        "分析屏幕截图，理解界面元素",
                        "根据计划选择下一步操作",
                        "确定点击、滑动、输入等具体动作",
                        "输出精确的操作坐标和参数"
                    ),
                    prompt = DEFAULT_EXECUTOR_PROMPT,
                    defaultPrompt = DEFAULT_EXECUTOR_PROMPT,
                    isDefault = true
                ),
                AgentPromptEntity(
                    id = "reflector",
                    name = "Reflector",
                    icon = "🔍",
                    role = "反思者",
                    description = "负责评估操作结果，判断动作是否成功执行。",
                    responsibilities = listOf(
                        "对比操作前后的屏幕变化",
                        "判断操作是否达到预期效果",
                        "识别异常情况（如弹窗、错误）",
                        "提供反馈帮助调整后续策略"
                    ),
                    prompt = DEFAULT_REFLECTOR_PROMPT,
                    defaultPrompt = DEFAULT_REFLECTOR_PROMPT,
                    isDefault = true
                ),
                AgentPromptEntity(
                    id = "notetaker",
                    name = "Notetaker",
                    icon = "📝",
                    role = "记录者",
                    description = "负责记录执行过程中的关键信息，供其他 Agent 参考。",
                    responsibilities = listOf(
                        "记录任务执行的重要节点",
                        "保存中间结果和状态信息",
                        "为后续步骤提供上下文参考",
                        "生成执行摘要和日志"
                    ),
                    prompt = DEFAULT_NOTETAKER_PROMPT,
                    defaultPrompt = DEFAULT_NOTETAKER_PROMPT,
                    isDefault = true
                )
            )

            for (agent in defaultAgents) {
                val cv = ContentValues().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("icon", agent.icon)
                    put("role", agent.role)
                    put("description", agent.description)
                    put("responsibilities", JSONArray(agent.responsibilities).toString())
                    put("prompt", agent.prompt)
                    put("default_prompt", agent.defaultPrompt)
                    put("is_default", 1)
                }
                db.insert("agent_prompts", null, cv)
            }
        }

        private fun insertDefaultToolsAndSkills(db: SQLiteDatabase) {
            val defaults = listOf(
                // Built-in Tools
                ToolSkillPromptEntity("search_apps", "search_apps", "搜索应用", "tool", "搜索手机中已安装的应用，匹配应用名称和包名", DEFAULT_TOOL_SEARCH_APPS, DEFAULT_TOOL_SEARCH_APPS, true),
                ToolSkillPromptEntity("open_app", "open_app", "打开应用", "tool", "通过应用名称打开指定的 App", DEFAULT_TOOL_OPEN_APP, DEFAULT_TOOL_OPEN_APP, true),
                ToolSkillPromptEntity("deep_link", "deep_link", "DeepLink 跳转", "tool", "通过 DeepLink URL 直接打开应用的特定页面", DEFAULT_TOOL_DEEP_LINK, DEFAULT_TOOL_DEEP_LINK, true),
                ToolSkillPromptEntity("clipboard", "clipboard", "剪贴板操作", "tool", "读取或写入系统剪贴板内容", DEFAULT_TOOL_CLIPBOARD, DEFAULT_TOOL_CLIPBOARD, true),
                ToolSkillPromptEntity("shell", "shell", "Shell 命令", "tool", "执行 ADB Shell 命令（需要 Shizuku 权限）", DEFAULT_TOOL_SHELL, DEFAULT_TOOL_SHELL, true),
                ToolSkillPromptEntity("http", "http", "HTTP 请求", "tool", "发送 HTTP 请求获取外部数据", DEFAULT_TOOL_HTTP, DEFAULT_TOOL_HTTP, true),
                ToolSkillPromptEntity("screenshot", "screenshot", "屏幕截图", "tool", "截取当前屏幕，获取界面图像供 AI 分析", DEFAULT_TOOL_SCREENSHOT, DEFAULT_TOOL_SCREENSHOT, true),
                ToolSkillPromptEntity("tap", "tap", "点击屏幕", "tool", "点击屏幕指定坐标位置", DEFAULT_TOOL_TAP, DEFAULT_TOOL_TAP, true),
                ToolSkillPromptEntity("swipe", "swipe", "滑动屏幕", "tool", "在屏幕上滑动，支持上下左右方向", DEFAULT_TOOL_SWIPE, DEFAULT_TOOL_SWIPE, true),
                ToolSkillPromptEntity("type", "type", "输入文本", "tool", "输入文本内容到当前焦点位置", DEFAULT_TOOL_TYPE, DEFAULT_TOOL_TYPE, true),
                ToolSkillPromptEntity("press_key", "press_key", "按键操作", "tool", "按下系统按键（Home、Back、Enter 等）", DEFAULT_TOOL_PRESS_KEY, DEFAULT_TOOL_PRESS_KEY, true),

                // Default Skills
                ToolSkillPromptEntity("order_food", "order_food", "外卖点餐", "skill", "在美团/饿了么等应用中完成外卖搜索与点餐流程", DEFAULT_SKILL_ORDER_FOOD, DEFAULT_SKILL_ORDER_FOOD, true),
                ToolSkillPromptEntity("ride_hailing", "ride_hailing", "打车出行", "skill", "在滴滴/高德等应用中输入目的地并呼叫网约车", DEFAULT_SKILL_RIDE_HAILING, DEFAULT_SKILL_RIDE_HAILING, true),
                ToolSkillPromptEntity("social_media", "social_media", "社交发帖", "skill", "在微信/小红书/微博等平台编辑并发布图文动态", DEFAULT_SKILL_SOCIAL_MEDIA, DEFAULT_SKILL_SOCIAL_MEDIA, true),
                ToolSkillPromptEntity("e_commerce", "e_commerce", "电商购物", "skill", "在淘宝/京东/拼多多中搜索商品并加入购物车", DEFAULT_SKILL_E_COMMERCE, DEFAULT_SKILL_E_COMMERCE, true),
                ToolSkillPromptEntity("music_player", "music_player", "音乐播放", "skill", "在网易云音乐/QQ音乐中搜索并播放指定歌曲", DEFAULT_SKILL_MUSIC_PLAYER, DEFAULT_SKILL_MUSIC_PLAYER, true),
                ToolSkillPromptEntity("navigation", "navigation", "地图导航", "skill", "在高德/百度地图中搜索路线并开始导航", DEFAULT_SKILL_NAVIGATION, DEFAULT_SKILL_NAVIGATION, true),
                ToolSkillPromptEntity("shell_calc", "shell_calc", "Shell 计算", "skill", "算术计算必须用 shell 执行 expr，不允许模型自己心算", DEFAULT_SKILL_SHELL_CALC, DEFAULT_SKILL_SHELL_CALC, true, domain = "计算", source = "builtin", executable = true)
            )

            for (item in defaults) {
                val cv = ContentValues().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("display_name", item.displayName)
                    put("category", item.category)
                    put("description", item.description)
                    put("prompt", item.prompt)
                    put("default_prompt", item.defaultPrompt)
                    put("is_default", 1)
                    put("enabled", if (item.enabled) 1 else 0)
                    put("keywords_json", item.keywordsJson)
                    put("params_json", item.paramsJson)
                    put("related_apps_json", item.relatedAppsJson)
                    put("prompt_hint", item.promptHint)
                    put("domain", item.domain)
                    put("source", item.source)
                    put("executable", if (item.executable) 1 else 0)
                }
                db.insert("tool_skill_prompts", null, cv)
            }
        }
    }

    companion object {
        /** addFeedback 遇到归一化后完全相同的反馈时返回。 */
        const val FEEDBACK_DUPLICATE = -2L

        @Volatile
        private var instance: PromptRepository? = null

        fun init(context: Context): PromptRepository {
            return instance ?: synchronized(this) {
                instance ?: PromptRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getInstance(): PromptRepository {
            return instance ?: throw IllegalStateException("PromptRepository 未初始化，请先调用 init()")
        }

        const val DEFAULT_MANAGER_PROMPT = """You are the planning agent for an Android automation app. Keep the plan short, concrete, and grounded in the current screen.

### User Request ###
{{instruction}}

{{completedPlan}}{{planSection}}{{lastActionSection}}{{lastSummarySection}}{{recentOutcomes}}{{importantNotes}}{{errorDetails}}{{skillContext}}{{additionalKnowledge}}

### Planning Rules ###
1. Choose the cheapest reliable action available in this run's Tool Policy.
   - Text input -> `type` (writes directly via ACTION_SET_TEXT; no keyboard switching).
   - Cursor/selection -> `set_selection`; page -> `scroll`; focus/navigation -> `key`.
   - Visible element with text -> `click` with exact `text`; coordinate only as fallback.
2. Do NOT plan to tap on-screen keyboard keys (`中`/`英`) or switch IME.
3. Do NOT plan screenshots or visual inspection unless the UI tree cannot answer the question.
4. Keep 3-6 subgoals. Update the plan only when the page changes or an action fails.
5. If the same action failed twice, switch strategy instead of retrying.
6. Use `answer` only for the final response when the task is complete. If required details are missing, use `ask_user` instead of guessing; for login/captcha use `take_over`.

{{phaseGuidance}}

### Output ###
### Thought ###
One or two sentences.
### Historical Operations ###
Completed subgoals (or "None yet").
### Plan ###
1. ...
2. ..."""

        const val DEFAULT_EXECUTOR_PROMPT = """You are the execution agent for an Android automation app. Choose exactly ONE next action.
The Accessibility UI Tree is authoritative; the screenshot is only a fallback for visual-only targets.

### User Request ###
{{instruction}}

### Overall Plan ###
{{plan}}

### Current Subgoal ###
{{currentSubgoal}}

### Progress Status ###
{{progressStatus}}

### Action Priority (cheapest reliable first) ###
1. TEXT INPUT -> `type`: click the input, then type exact text via ACTION_SET_TEXT. Do not click keyboard keys or switch `中`/`英`.
2. TEXT EDITING -> `set_selection`: start/end indices on the focused input; no long-press drag.
3. PAGE / LIST -> `scroll`: accessibility scroll; use `swipe` only for drag/sliders or if scroll fails.
4. FOCUS / NAV -> `key`: ENTER/BACK/HOME/TAB/DPAD_*/PAGE_UP/PAGE_DOWN/MOVE_HOME/MOVE_END/CTRL+A.
5. ELEMENT CLICK -> `click` with exact `text` from the UI tree; center `coordinate` as fallback.
6. VISUAL-ONLY -> `click` with absolute pixels (1080x2400), never normalized.

### Hard Rules ###
- Text > coordinates; UI tree > screenshot. Do not take screenshots when the UI tree has the target.
- If the same action failed twice, change strategy: type -> set_selection -> key -> scroll -> click(text) -> click(coordinate).
- Close popups/dialogs before proceeding.
- Use `answer` only for the final response. If required details are missing, use `ask_user` instead of guessing; for login/captcha use `take_over`.
- Stay on the current subgoal; do not jump ahead.

#### Sensitive Operations ####
For payment confirmation, password input, or biometric verification, do not execute directly.
Add `message` to the action to request confirmation:
{"action":"click","coordinate":[500,800],"message":"确认支付 ¥100"}
Do not stop for price displays, payment method selection, cart pages, or normal navigation.

#### Actions ####
Allowed: click, type, set_selection, scroll, key, swipe, system_button, wait, take_over, ask_user, answer, terminate.
Examples:
{"action":"ask_user","message":"请问是哪个城市的潮汐？"}
{"action":"click","text":"显示与亮度","coordinate":[316,730]}
{"action":"type","text":"hello"}
{"action":"set_selection","start":3,"end":8}
{"action":"scroll","direction":"down"}
{"action":"key","key":"ENTER"}
{"action":"terminate","status":"success"}

### Latest Action History ###
{{actionHistory}}

### Output ###
### Thought ###
1. **Screen**: ...
2. **Target**: ...
3. **Decision**: ...
### Action ###
One valid JSON object, no markdown fences.
### Description ###
One short sentence."""

        const val DEFAULT_REFLECTOR_PROMPT = """You are the reflection agent. Compare the BEFORE and AFTER screenshots and the action, and judge whether it succeeded.

### User Request ###
{{instruction}}

### Progress ###
{{completedPlan}}

### Last Action ###
{{lastAction}}
Expectation: {{lastSummary}}

### Judging Rules ###
- A = the screen changed as expected (or the expected state is already reached).
- B = wrong page / wrong target; explain what went wrong and which cheaper action should have been used.
- C = no visible change / same page; explain why.
- If the action used touch/UI where a cheaper available action would be better, mark B and say so explicitly.
- If text input tapped the on-screen keyboard instead of `type`/ACTION_SET_TEXT, mark B.
- Do not mark A just because a UI opened; compare with the expectation.
- Keep the error description to 1-2 sentences.

### Output ###
### Outcome ###
A/B/C
### Error Description ###
..."""

        const val DEFAULT_NOTETAKER_PROMPT = """You are the note-taking agent. Record only facts that will help future steps: file paths, package names, account names, UI state, errors. Do not describe screenshots. Keep it under 8 lines.

### User Request ###
{{instruction}}

### Progress ###
{{progressStatus}}

### Existing Notes ###
{{importantNotes}}

### Output ###
### Important Notes ###
..."""

        const val DEFAULT_TOOL_SEARCH_APPS = "工具: search_apps\n说明: 搜索手机中已安装的应用，匹配应用名称和包名\n参数:\n  - query: string (必填) - 搜索关键词"
        const val DEFAULT_TOOL_OPEN_APP = "工具: open_app\n说明: 通过应用名称打开指定的 App\n参数:\n  - text: string (必填) - 应用名称（如\"微信\"、\"设置\"）"
        const val DEFAULT_TOOL_DEEP_LINK = "工具: deep_link\n说明: 通过 DeepLink URL 直接打开应用的特定页面\n参数:\n  - url: string (必填) - DeepLink 链接"
        const val DEFAULT_TOOL_CLIPBOARD = "工具: clipboard\n说明: 读取或写入系统剪贴板内容\n参数:\n  - action: string (必填) - 操作类型 (read/write)\n  - text: string (可选) - 要写入剪贴板的文本"
        const val DEFAULT_TOOL_SHELL = "工具: shell\n说明: 执行 ADB Shell 命令（需要 Shizuku 权限）\n参数:\n  - command: string (必填) - 要执行的 Shell 命令"
        const val DEFAULT_TOOL_HTTP = "工具: http\n说明: 发送 HTTP 请求获取外部数据\n参数:\n  - url: string (必填) - 请求 URL\n  - method: string (可选) - 请求方法 (GET/POST)"
        const val DEFAULT_TOOL_SCREENSHOT = "工具: screenshot\n说明: 截取当前屏幕，获取界面图像供 AI 分析"
        const val DEFAULT_TOOL_TAP = "工具: tap\n说明: 点击屏幕指定坐标位置\n参数:\n  - coordinate: list (必填) - 点击坐标 [x, y]"
        const val DEFAULT_TOOL_SWIPE = "工具: swipe\n说明: 在屏幕上滑动，支持上下左右方向\n参数:\n  - coordinate: list (必填) - 起始坐标 [x1, y1]\n  - coordinate2: list (必填) - 终点坐标 [x2, y2]"
        const val DEFAULT_TOOL_TYPE = "工具: type\n说明: 输入文本内容到当前焦点位置\n参数:\n  - text: string (必填) - 输入的文本"
        const val DEFAULT_TOOL_PRESS_KEY = "工具: press_key\n说明: 按下系统按键（Home、Back、Enter 等）\n参数:\n  - button: string (必填) - 按键名称"

        const val DEFAULT_SKILL_ORDER_FOOD = "技能: order_food\n说明: 外卖点餐服务。根据用户想吃的食物，搜索商家并选择商品下订单。"
        const val DEFAULT_SKILL_RIDE_HAILING = "技能: ride_hailing\n说明: 打车出行服务。输入目的地坐标或名称，呼叫合适车型的网约车。"
        const val DEFAULT_SKILL_SOCIAL_MEDIA = "技能: social_media\n说明: 社交发帖服务。选择图文素材并发布到社交应用平台。"
        const val DEFAULT_SKILL_E_COMMERCE = "技能: e_commerce\n说明: 电商购物服务。搜索商品、对比价格并加入购物车或下单。"
        const val DEFAULT_SKILL_MUSIC_PLAYER = "技能: music_player\n说明: 音乐播放服务。搜索音乐名称、播放指定歌曲或歌单。"
        const val DEFAULT_SKILL_NAVIGATION = "技能: navigation\n说明: 地图导航服务。设置终点并开启语音路线导航。"
        const val DEFAULT_SKILL_SHELL_CALC = "技能: shell_calc\n说明: 算术计算必须用 shell 执行 expr，不允许模型自己心算。\n步骤:\n1. 将用户表达式转成 shell expr 命令。\n2. 调用 shell 动作执行。\n3. 用 shell 输出作为最终 answer。"
    }
}
