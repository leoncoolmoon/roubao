package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * 运行时 Tool Policy。由 DB 的 dependence/enabled + 当前权限等级生成，
 * 只列当前环境真正能调用的动作，避免模型把步数浪费在不可用工具上。
 * 注意：Agent 策略包（pack）只影响 Agent prompt，不参与这里的过滤。
 */
fun toolPolicySection(
    level: AccessLevel = AccessLevelManager.current()
): String {
    val repo = try {
        PromptRepository.getInstance()
    } catch (e: Exception) {
        return ""
    }

    val dispatchable = setOf(
        "tap", "type", "swipe", "press_key", "open_app", "search_apps", "shell", "root_shell", "file"
    )
    val items = try {
        repo.getToolSkillPromptsForLevel(level)
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
        "search_apps" to "{\"action\":\"search_apps\",\"text\":\"应用名关键词\"}",
        "shell" to "{\"action\":\"shell\",\"command\":\"ls /sdcard/Download\"}",
        "root_shell" to "{\"action\":\"root_shell\",\"command\":\"id\"}",
        "file" to "{\"action\":\"file\",\"op\":\"list|read|mkdir|copy|move|delete|write\"," +
            "\"path\":\"相对授权目录的路径\",\"dest\":\"copy/move 目标\",\"text\":\"write 的内容\"}"
    )

    return buildString {
        append("\n\n### Tool Policy (AUTHORITATIVE) ###\n")
        append("Permission level: ").append(level.id).append(" (").append(level.label).append(")\n")
        append("Enabled tools:\n")
        items.sortedBy { it.id }.forEach { item ->
            append("- ").append(item.id)
            val deps = AccessLevel.parse(item.dependenceJson)
            append(" [").append(if (deps.isEmpty()) "all levels" else deps.joinToString("+")).append("]")
            usage[item.id]?.let { append(": ").append(it) }
            append('\n')
        }
        if (items.any { it.id == "file" }) {
            append("file 授权目录: ").append(fileRoot())
                .append("（越界路径会被拒绝；move / delete / 覆盖已有文件需要用户批准）\n")
        }
        if (items.none { it.id == "shell" }) {
            append("shell/root are NOT available at this permission level; use UI actions or ask the user to enable Shizuku.\n")
        }
        if (level.rank < AccessLevel.ADB.rank) {
            append("Only UI actions are available: click, type, swipe, scroll, set_selection, system_button(BACK/HOME), open_app (normal Intent). Launch apps from Home/app drawer or open_app.\n")
        }
    }.trimEnd()
}

/** file 工具的授权目录（与设置页保持一致）。 */
private fun fileRoot(): String = try {
    com.roubao.autopilot.App.getInstance().settingsManager.settings.value.fileAccessRoot
} catch (e: Exception) {
    "/sdcard/Download/肉包"
}
