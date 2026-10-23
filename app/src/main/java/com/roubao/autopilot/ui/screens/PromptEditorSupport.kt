package com.roubao.autopilot.ui.screens

/**
 * Prompt 编辑页可插入的动态占位符。
 * token 必须与运行时替换逻辑完全一致。
 */
data class PlaceholderHelpItem(
    val token: String,
    val purpose: String,
    val example: String
)

val PROMPT_PLACEHOLDERS = listOf(
    PlaceholderHelpItem(
        "{{instruction}}",
        "用户当前的任务/指令原文。",
        "打开设置，把屏幕亮度调到 50%"
    ),
    PlaceholderHelpItem(
        "{{plan}}",
        "Manager 当前生成的完整计划。",
        "1. 打开设置\n2. 进入显示与亮度\n3. 调整亮度"
    ),
    PlaceholderHelpItem(
        "{{progressStatus}}",
        "已完成步骤和当前进度的摘要。",
        "已打开设置，下一步进入显示与亮度"
    ),
    PlaceholderHelpItem(
        "{{completedPlan}}",
        "已完成步骤/历史计划，没有时显示 No progress yet。",
        "1. 打开设置 ✓"
    ),
    PlaceholderHelpItem(
        "{{lastAction}}",
        "上一步动作的 JSON。",
        "click coordinate 500,800"
    ),
    PlaceholderHelpItem(
        "{{lastSummary}}",
        "上一步动作的简短描述。",
        "点击了“显示与亮度”"
    ),
    PlaceholderHelpItem(
        "{{actionHistory}}",
        "最近动作、结果和错误组成的列表。",
        "click 显示与亮度 -> A（成功）"
    ),
    PlaceholderHelpItem(
        "{{recentOutcomes}}",
        "最近动作的结果标记（A=成功，B=错误页，C=无变化）。",
        "A, A, C"
    ),
    PlaceholderHelpItem(
        "{{importantNotes}}",
        "Notetaker 记录的重要信息。",
        "该页面需要先关闭广告弹窗"
    ),
    PlaceholderHelpItem(
        "{{errorDetails}}",
        "最近失败动作的错误描述。",
        "点击后页面没有变化"
    ),
    PlaceholderHelpItem(
        "{{planSection}}",
        "计划区块，有内容时插入；没有时为空。",
        "### Plan ###\n1. 打开设置..."
    ),
    PlaceholderHelpItem(
        "{{lastActionSection}}",
        "上一步动作区块，有内容时插入；没有时为空。",
        "### Last Action ###\nclick..."
    ),
    PlaceholderHelpItem(
        "{{lastSummarySection}}",
        "上一步摘要区块，有内容时插入；没有时为空。",
        "### Last Summary ###\n点击了..."
    ),
    PlaceholderHelpItem(
        "{{skillContext}}",
        "匹配到的 Skill Prompt 上下文。",
        "技能: music_player\n说明: 音乐播放服务..."
    ),
    PlaceholderHelpItem(
        "{{additionalKnowledge}}",
        "补充知识/额外说明，有内容时插入。",
        "该 App 的搜索按钮在右上角"
    )
)

private val PLACEHOLDER_REGEX = Regex("""\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}""")
private val ALLOWED_PLACEHOLDERS = PROMPT_PLACEHOLDERS.map { it.token }.toSet()

/**
 * 保存前做结构检查。
 *
 * @param name Agent 名称，或 Tool/Skill 的标识符 name
 * @param category "agent" / "tool" / "skill"
 * @param agentId 默认 Agent 的 id（manager/executor/reflector/notetaker），自定义 Agent 为 null
 * @return null 表示通过，否则返回给用户看的错误信息
 */
fun validatePromptFormat(
    name: String,
    category: String,
    agentId: String?,
    prompt: String
): String? {
    val text = prompt.trim()
    if (text.isBlank()) {
        return if (category == "agent") "Prompt 不能为空" else null
    }

    if (category == "tool" || category == "skill") {
        return validateToolSkillPrompt(name, category, text)
    }

    // Agent Prompt：检查占位符拼写，避免模型收到永远不会被替换的字面量
    val matches = PLACEHOLDER_REGEX.findAll(text).toList()
    val unknown = matches.map { it.value }.filter { it !in ALLOWED_PLACEHOLDERS }.distinct()
    if (unknown.isNotEmpty()) {
        return "未知占位符：${unknown.joinToString("、")}。请从占位符帮助页插入。"
    }

    val withoutPlaceholders = PLACEHOLDER_REGEX.replace(text, "")
    if ("{{" in withoutPlaceholders || "}}" in withoutPlaceholders) {
        return "占位符括号不匹配，请检查是否漏了 {{ 或 }}。"
    }

    if ("{{instruction}}" !in text) {
        return "Agent Prompt 必须包含 {{instruction}}，否则模型看不到用户任务。"
    }

    val required = when (agentId) {
        "executor" -> listOf("{{instruction}}", "{{plan}}", "{{progressStatus}}", "{{actionHistory}}")
        "reflector" -> listOf("{{instruction}}", "{{completedPlan}}", "{{lastAction}}", "{{lastSummary}}")
        "notetaker" -> listOf("{{instruction}}", "{{progressStatus}}", "{{importantNotes}}")
        else -> listOf("{{instruction}}")
    }
    val missing = required.filter { it !in text }
    if (missing.isNotEmpty()) {
        return "该 Agent 缺少必需占位符：${missing.joinToString("、")}"
    }

    return null
}

private fun validateToolSkillPrompt(name: String, category: String, text: String): String? {
    // 自定义 Tool/Skill 允许空 Prompt，此时使用 Tool 的默认描述；一旦写入内容就必须符合格式。
    if (text.isBlank()) return null

    if ("{{" in text || "}}" in text) {
        return "Tool/Skill Prompt 不支持动态占位符；占位符只用于 Agent Prompt。"
    }

    val heading = if (category == "tool") "工具" else "技能"
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (firstLine != "$heading: $name") {
        return "第一行必须是「$heading: $name」，不能改工具/技能标识符。"
    }

    val descriptionRegex = Regex("""(?m)^说明:\s*\S""")
    if (!descriptionRegex.containsMatchIn(text)) {
        return "缺少「说明: ...」行，说明不能为空。"
    }

    if (category == "tool") {
        val lines = text.lines()
        val paramLines = lines.filter { it.trim().startsWith("-") }
        if (paramLines.isNotEmpty() && lines.none { it.trim() == "参数:" }) {
            return "参数行必须放在「参数:」行下面。"
        }

        val paramRegex = Regex(
            """^\s*-\s*[A-Za-z_][A-Za-z0-9_]*\s*:\s*(string|int|integer|boolean|list|number)\s*(\((必填|可选)\))?\s*-\s*\S.*$"""
        )
        paramLines.firstOrNull { !paramRegex.matches(it.trim()) }?.let {
            return "参数行格式错误：$it\n正确格式：- name: string (必填) - 说明"
        }
    }

    return null
}
