package com.roubao.autopilot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

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
    val enabled: Boolean = true
)

data class UserFeedbackEntity(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val enabled: Boolean,
    val kind: String = "complaint" // "suggestion" or "complaint"
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

/** 旧预设中与默认 Prompt 重复的反馈，v5 迁移时一次性删除。 */
private val LEGACY_REDUNDANT_FEEDBACK_TEXTS = setOf(
    "文件/系统任务优先用 shell（ls/find/grep/cp/mv/mkdir/rm/stat），不要打开文件管理器 UI。",
    "输入文字优先用 type（ACTION_SET_TEXT），不要点键盘中/英。",
    "选择文字/移动光标优先用 set_selection，不要长按拖拽。",
    "翻页优先用 scroll，不要反复 swipe。",
    "能用无障碍 UI 树文本定位，就不要靠截图猜坐标。",
    "截图只在需要视觉判断时使用，不要每一步都依赖截图。",
    "不要打开文件管理器做文件操作，任务会因此失败。",
    "不要点键盘中/英切输入法，type 会直接写入。",
    "不要长按拖拽选字，用 set_selection。",
    "不要反复点同一个位置；失败两次必须换策略。"
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
                    isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1
                )
            }
        }
        return null
    }

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
                        enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) == 1
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
                    isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1
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
                        kind = it.getString(it.getColumnIndexOrThrow("kind"))
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
    fun getFeedbackPrompt(): String {
        return try {
            val active = getAllFeedback()
                .filter { it.enabled && it.text.isNotBlank() }
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
        root.put("version", 1)
        root.put("exported_at", System.currentTimeMillis())

        val agents = JSONArray()
        getAllAgentPrompts().forEach { agent ->
            agents.put(JSONObject().apply {
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
            })
        }
        root.put("agents", agents)

        val tools = JSONArray()
        getAllToolSkillPrompts().forEach { item ->
            tools.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("display_name", item.displayName)
                put("category", item.category)
                put("description", item.description)
                put("prompt", item.prompt)
                put("default_prompt", item.defaultPrompt)
                put("is_default", item.isDefault)
                put("enabled", item.enabled)
            })
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
                    val prompt = obj.optString("prompt")
                    if (prompt.isBlank()) {
                        errors.add("tool $id 缺少 prompt")
                        continue
                    }
                    val category = obj.optString("category", "tool")
                    if (category != "tool" && category != "skill") {
                        errors.add("tool $id 的 category 必须是 tool 或 skill")
                        continue
                    }
                    val cv = ContentValues().apply {
                        put("id", id)
                        put("name", obj.optString("name", id))
                        put("display_name", obj.optString("display_name", obj.optString("name", id)))
                        put("category", category)
                        put("description", obj.optString("description", ""))
                        put("prompt", prompt)
                        put("default_prompt", obj.optString("default_prompt", prompt))
                        put("is_default", if (obj.optBoolean("is_default", false)) 1 else 0)
                        put("enabled", if (obj.optBoolean("enabled", true)) 1 else 0)
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

    private class PromptOpenHelper(context: Context) : SQLiteOpenHelper(context, "prompts.db", null, 5) {

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
                    enabled INTEGER NOT NULL DEFAULT 1
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
                    kind TEXT NOT NULL DEFAULT 'complaint'
                )
                """.trimIndent()
            )

            insertDefaultAgents(db)
            insertDefaultToolsAndSkills(db)
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
                cleanupRedundantAndDuplicateFeedback(db)
            }
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

        /** v5 一次性清理：旧预设中与默认 Prompt 重复的反馈 + 归一化后的完全重复反馈。 */
        private fun cleanupRedundantAndDuplicateFeedback(db: SQLiteDatabase) {
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
                    if (normalized in LEGACY_REDUNDANT_FEEDBACK_TEXTS || !seen.add(normalized)) {
                        toDelete.add(id)
                    }
                }
            }
            for (id in toDelete) {
                db.delete("user_feedback", "id = ?", arrayOf(id.toString()))
            }
            if (toDelete.isNotEmpty()) {
                println("[PromptRepository] removed ${toDelete.size} redundant feedback rows")
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
                ToolSkillPromptEntity("navigation", "navigation", "地图导航", "skill", "在高德/百度地图中搜索路线并开始导航", DEFAULT_SKILL_NAVIGATION, DEFAULT_SKILL_NAVIGATION, true)
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

        const val DEFAULT_MANAGER_PROMPT = """You are the planning agent for an Android automation app. Keep the plan short, concrete, and tool-first.

### User Request ###
{{instruction}}

{{completedPlan}}{{planSection}}{{lastActionSection}}{{lastSummarySection}}{{recentOutcomes}}{{importantNotes}}{{errorDetails}}{{skillContext}}{{additionalKnowledge}}

### Planning Rules ###
1. Prefer the cheapest reliable action:
   - File/system tasks -> `shell` (ls/find/grep/cp/mv/mkdir/rm/stat/du/pm/dumpsys/content). Never plan to open the file manager UI for file operations.
   - Text input -> `type` (writes directly via ACTION_SET_TEXT; no keyboard switching).
   - Cursor/selection -> `set_selection`; page -> `scroll`; focus/navigation -> `key`.
   - Visible element with text -> `click` with exact `text`; coordinate only as fallback.
2. Do NOT plan to tap on-screen keyboard keys (`中`/`英`) or switch IME.
3. Do NOT plan screenshots or visual inspection unless the UI tree cannot answer the question.
4. Keep 3-6 subgoals. Update the plan only when the page changes or an action fails.
5. If the same action failed twice, switch strategy instead of retrying.
6. For questions use `answer`; for login/captcha use `take_over`.

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
1. FILE / SYSTEM -> `shell`: files, packages, settings, content (ls/find/grep/cat/cp/mv/mkdir/rm/stat/du/pm/dumpsys/content query). Never open File Manager for file operations.
2. TEXT INPUT -> `type`: click the input, then type exact text via ACTION_SET_TEXT. Do not click keyboard keys or switch `中`/`英`.
3. TEXT EDITING -> `set_selection`: start/end indices on the focused input; no long-press drag.
4. PAGE / LIST -> `scroll`: accessibility scroll; use `swipe` only for drag/sliders or if scroll fails.
5. FOCUS / NAV -> `key`: ENTER/BACK/HOME/TAB/DPAD_*/PAGE_UP/PAGE_DOWN/MOVE_HOME/MOVE_END/CTRL+A.
6. ELEMENT CLICK -> `click` with exact `text` from the UI tree; center `coordinate` as fallback.
7. VISUAL-ONLY -> `click` with absolute pixels (1080x2400), never normalized.

### Hard Rules ###
- Text > coordinates; UI tree > screenshot. Do not take screenshots when the UI tree has the target.
- If the same action failed twice, change strategy: shell -> type -> set_selection -> key -> scroll -> click(text) -> click(coordinate).
- Close popups/dialogs before proceeding.
- For questions use `answer`; for login/captcha use `take_over`.
- Stay on the current subgoal; do not jump ahead.

#### Sensitive Operations ####
For payment confirmation, password input, or biometric verification, do not execute directly.
Add `message` to the action to request confirmation:
{"action":"click","coordinate":[500,800],"message":"确认支付 ¥100"}
Do not stop for price displays, payment method selection, cart pages, or normal navigation.

#### Actions ####
Allowed: shell, click, type, set_selection, scroll, key, swipe, system_button, open_app, wait, take_over, answer, terminate.
Examples:
{"action":"shell","command":"ls /sdcard/Download"}
{"action":"click","text":"显示与亮度","coordinate":[316,730]}
{"action":"type","text":"hello"}
{"action":"set_selection","start":3,"end":8}
{"action":"scroll","direction":"down"}
{"action":"key","key":"ENTER"}
{"action":"open_app","text":"设置"}  Available apps: {{installedApps}}
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
- If the action used touch/UI where `shell`, `type`, `set_selection`, `scroll`, or `key` would be better, mark B and say so explicitly.
- If a file operation opened the file manager instead of using shell, mark B: "use shell".
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
    }
}
