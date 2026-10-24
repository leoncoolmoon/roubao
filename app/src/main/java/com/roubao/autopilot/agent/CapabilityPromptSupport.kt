package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * 运行时能力策略。由数据库 enabled 状态生成，覆盖 Prompt 中静态写死的能力推荐。
 */
fun toolPolicySection(): String {
    // 只投影已经有 Action 执行分支的 Tool；search_apps/http/clipboard 等暂无通用 tool action。
    val dispatchableToolIds = setOf("shell", "open_app")
    val tools = try {
        PromptRepository.getInstance().getAllToolSkillPrompts()
            .filter { it.category == "tool" && it.id in dispatchableToolIds }
    } catch (e: Exception) {
        return ""
    }
    if (tools.isEmpty()) return ""

    val enabled = tools.filter { it.enabled }.map { it.id }
    val disabled = tools.filterNot { it.enabled }.map { it.id }
    return buildString {
        append("\n\n### Tool Policy (AUTHORITATIVE) ###\n")
        if (enabled.isNotEmpty()) {
            append("Enabled tools: ").append(enabled.joinToString(", ")).append('\n')
        }
        if (disabled.isNotEmpty()) {
            append("Disabled tools: ").append(disabled.joinToString(", ")).append('\n')
            append("If a tool is disabled, NEVER choose it, even if another section mentions it.\n")
        }
    }.trimEnd()
}
