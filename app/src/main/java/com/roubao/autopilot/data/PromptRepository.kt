package com.roubao.autopilot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

data class AgentPromptEntity(
    val id: String,
    val name: String,
    val icon: String,
    val role: String,
    val description: String,
    val responsibilities: List<String>,
    val prompt: String,
    val defaultPrompt: String,
    val isDefault: Boolean
)

data class ToolSkillPromptEntity(
    val id: String,
    val name: String,
    val displayName: String,
    val category: String, // "tool" or "skill"
    val description: String,
    val prompt: String,
    val defaultPrompt: String,
    val isDefault: Boolean
)

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
                        isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1
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
                        isDefault = it.getInt(it.getColumnIndexOrThrow("is_default")) == 1
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
        }
        db.insertWithOnConflict("tool_skill_prompts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteToolSkillPrompt(id: String): Boolean {
        val item = getToolSkillPrompt(id) ?: return false
        if (item.isDefault) return false
        val db = dbHelper.writableDatabase
        return db.delete("tool_skill_prompts", "id = ?", arrayOf(id)) > 0
    }

    private class PromptOpenHelper(context: Context) : SQLiteOpenHelper(context, "prompts.db", null, 2) {

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
                    is_default INTEGER NOT NULL DEFAULT 1
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
                    is_default INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )

            insertDefaultAgents(db)
            insertDefaultToolsAndSkills(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS agent_prompts")
            db.execSQL("DROP TABLE IF EXISTS tool_skill_prompts")
            onCreate(db)
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

        const val DEFAULT_MANAGER_PROMPT = """You are an agent who can operate an Android phone on behalf of a user. Your goal is to track progress and devise/update high-level plans to achieve the user's requests.

### User Request ###
{{instruction}}

{{completedPlan}}
{{planSection}}
{{lastActionSection}}
{{lastSummarySection}}
{{recentOutcomes}}
{{importantNotes}}
{{errorDetails}}
{{skillContext}}

### Guidelines ###
1. IMPORTANT: If you see the "肉包" or "Baozi" app interface (this automation tool), press Home button first to go back to the home screen, then proceed with the task.
2. ALWAYS use `open_app` action to open apps - NEVER go to home screen to look for app icons! The open_app action can launch any installed app directly.
3. Use search to quickly find a file or entry with a specific name.
4. If there are relevant skills listed above, follow their suggested steps for better efficiency.
{{additionalKnowledge}}

### ⛔ SECURITY: Sensitive Pages - MUST STOP ###
ONLY output "STOP_SENSITIVE" when the screen is ACTIVELY REQUESTING payment confirmation, password input, or biometric verification.

### CRITICAL: When to mark "Finished" ###
ONLY mark as "Finished" when ALL of these are true:
1. The user's request has been FULLY completed (not partially)
2. You can SEE the final success state in the screenshot
3. The last action was SUCCESSFUL (outcome A)
4. There are NO recent consecutive failures

Provide your output in the following format:

### Thought ###
Your rationale for the plan or updated plan.

### Historical Operations ###
Add newly completed subgoals on top of existing ones (if updating an existing plan).

### Plan ###
Updated plan or "Finished" if truly done.
1. first subgoal
2. second subgoal
..."""

        const val DEFAULT_EXECUTOR_PROMPT = """You are an agent who can operate an Android phone. Decide the next action based on the current state.

### User Request ###
{{instruction}}

### Overall Plan ###
{{plan}}

### Progress Status ###
{{progressStatus}}

### Guidelines ###
General:
- For any pop-up window, close it (e.g., by clicking 'Don't Allow' or 'Accept') before proceeding.
- For requests that are questions, remember to use the `answer` action to reply before finish!
- If the desired state is already achieved, you can just complete the task.

Action Related:
- Use `open_app` to open an app, do not use the app drawer.
- Consider using `swipe` to reveal additional content.
- If swiping doesn't change the page, it may have reached the bottom.

Text Related:
- To input text: first click the input box, make sure keyboard is visible, then use `type` action.
- To clear text: long press the backspace button in the keyboard.

#### Atomic Actions ####
- click(coordinate): Click at (x, y). Example: {"action": "click", "coordinate": [x, y]}
- double_tap(coordinate): Double tap at (x, y) for zoom or like. Example: {"action": "double_tap", "coordinate": [x, y]}
- long_press(coordinate): Long press at (x, y). Example: {"action": "long_press", "coordinate": [x, y]}
- type(text): Type text into activated input box. Example: {"action": "type", "text": "hello"}
- swipe(coordinate, coordinate2): Swipe from point1 to point2. Example: {"action": "swipe", "coordinate": [x1, y1], "coordinate2": [x2, y2]}
- system_button(button): Press Back/Home/Enter. Example: {"action": "system_button", "button": "Back"}
- open_app(text): Open an app by name. Example: {"action": "open_app", "text": "设置"}
- wait(duration): Wait for page loading. Duration in seconds (1-10). Example: {"action": "wait", "duration": 3}
- take_over(message): Request user to manually complete login/captcha/verification. Example: {"action": "take_over", "message": "请完成登录验证"}
- answer(text): Answer user's question. Example: {"action": "answer", "text": "The answer is..."}

### Latest Action History ###
{{actionHistory}}

IMPORTANT:
1. Do NOT repeat previously failed actions. Try a different approach.
2. Prioritize the current subgoal.
3. Always analyze the screen BEFORE deciding on an action.

Provide your output in the following format:

### Thought ###
1. **Screen**: What app/page is currently shown? Is it the expected page for the current subgoal?
2. **Blocker**: Any popup, dialog, keyboard, or loading state that needs to be handled first?
3. **Target**: Is the target element visible? If not, should I scroll or navigate?
4. **Decision**: Based on the above analysis, what action should I take and why?

### Action ###
A valid JSON specifying the action. Example: {"action":"click", "coordinate": [500, 800]}

### Description ###
A brief description of the chosen action."""

        const val DEFAULT_REFLECTOR_PROMPT = """You are an agent verifying whether the last action produced the expected behavior.

### User Request ###
{{instruction}}

### Progress Status ###
{{completedPlan}}

The two attached images are phone screenshots taken BEFORE and AFTER your last action.

### Latest Action ###
Action: {{lastAction}}
Expectation: {{lastSummary}}

Carefully examine whether the last action produced the expected behavior.

Note: For swiping to scroll, if the content before and after is exactly the same, the swipe is considered Failed (C) - the page may have reached the bottom.

Provide your output in the following format:

### Outcome ###
Choose from:
A: Successful. The result meets the expectation.
B: Failed. The action resulted in a wrong page. Need to return to previous state.
C: Failed. The action produced no changes.

### Error Description ###
If failed, describe the error. If successful, put "None"."""

        const val DEFAULT_NOTETAKER_PROMPT = """You are a helpful AI assistant for operating mobile phones. Your goal is to take notes of important content relevant to the user's request.

### User Request ###
{{instruction}}

### Progress Status ###
{{progressStatus}}

### Existing Important Notes ###
{{importantNotes}}

Examine the current screen to identify any important content that needs to be recorded.

IMPORTANT:
- Do not take notes on low-level actions
- Only keep track of significant textual or visual information relevant to the request
- Do not repeat user request or progress status
- Do not make up content that you are not sure about

Provide your output in the following format:

### Important Notes ###
The updated important notes, combining old and new ones. If nothing new to record, copy the existing important notes."""

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
