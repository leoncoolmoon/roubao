package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * 运行时 Tool Policy。由 DB 的 dependence/enabled + 当前等级/包生成，
 * 只列当前环境真正能调用的动作，避免模型把步数浪费在不可用工具上。
 */
fun toolPolicySection(
    packId: String = "standard",
    level: AccessLevel = AccessLevelManager.current()
): String {
    val repo = try {
        PromptRepository.getInstance()
    } catch (e: Exception) {
        return ""
    }

    val packLevel = try {
        repo.getPack(packId)?.level?.let { AccessLevel.fromId(it) } ?: AccessLevel.ROOT
    } catch (e: Exception) {
        AccessLevel.ROOT
    }
    val effective = AccessLevel.cap(level, packLevel)

    val dispatchable = setOf("tap", "type", "swipe", "press_key", "open_app", "shell")
    val items = try {
        repo.getToolSkillPromptsForLevel(effective)
            .filter { it.category == "tool" && it.id in dispatchable && it.enabled }
    } catch (e: Exception) {
        emptyList()
    }
    if (items.isEmpty()) return ""

    val usage = mapOf(
        "tap" to "{\"action\":\"click\",\"text\":\"元素文本\",\"coordinate\":[x,y]}",
        "type" to "{\"action\":\"type\",\"text\":\"要输入的文字\"}",
        "swipe" to "{\"action\":\"swipe\",\"direction\":\"up|down|left|right\"}",
        "press_key" to "{\"action\":\"key\",\"key\":\"ENTER|BACK|HOME|TAB|DPAD_DOWN\"}",
        "open_app" to "{\"action\":\"open_app\",\"text\":\"应用名\"}",
        "shell" to "{\"action\":\"shell\",\"command\":\"ls /sdcard/Download\"}"
    )

    return buildString {
        append("\n\n### Tool Policy (AUTHORITATIVE) ###\n")
        append("Access level: ").append(effective.id).append(" (").append(effective.label).append(")")
        append(" | pack: ").append(packId).append('\n')
        append("Enabled tools:\n")
        items.sortedBy { it.id }.forEach { item ->
            append("- ").append(item.id)
            val deps = AccessLevel.parse(item.dependenceJson)
            append(" [").append(if (deps.isEmpty()) "all levels" else deps.joinToString("+")).append("]")
            usage[item.id]?.let { append(": ").append(it) }
            append('\n')
        }
        if (items.none { it.id == "shell" }) {
            append("shell/root are NOT available in this pack; use UI actions or ask the user to enable Shizuku.\n")
        }
        if (effective.rank < AccessLevel.ADB.rank) {
            append("Only UI actions are available: click, type, swipe, scroll, set_selection, system_button(BACK/HOME), open_app (normal Intent). Launch apps from Home/app drawer or open_app.\n")
        }
    }.trimEnd()
}
