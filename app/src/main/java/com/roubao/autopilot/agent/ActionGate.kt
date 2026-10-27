package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * 执行前门禁。
 *
 * dependence / level 只是可见性提示，但这里仍然要保证：
 * - 工具存在且没有被用户停用；
 * - 当前等级满足工具的 dependence；
 * - 没有 binding 的工具不会被执行。
 *
 * 真正的系统权限由 provider 决定；这里只是把“明显不可能的调用”挡掉，
 * 避免模型浪费步数。
 */
object ActionGate {

    private val actionToItem = mapOf(
        "click" to "tap",
        "double_tap" to "tap",
        "long_press" to "tap",
        "type" to "type",
        "swipe" to "swipe",
        "key" to "press_key",
        "open_app" to "open_app",
        "search_apps" to "search_apps",
        "shell" to "shell",
        "root_shell" to "root_shell",
        "file" to "file"
    )

    fun check(action: Action, level: AccessLevel): String? {
        // 模型有时把 system_button(BACK/HOME) 写成 key；这两个键无障碍可以直接处理。
        if (action.type == "key" && action.key?.lowercase() in setOf("back", "home")) {
            return null
        }
        val itemId = actionToItem[action.type] ?: return null
        val repo = try {
            PromptRepository.getInstance()
        } catch (e: Exception) {
            return "prompt repository unavailable"
        }
        val item = try {
            repo.getToolSkillPrompt(itemId)
        } catch (e: Exception) {
            null
        } ?: return "tool '$itemId' not found"

        if (!item.enabled) return "tool '$itemId' is disabled"
        if (!AccessLevel.satisfies(level, item.dependenceJson)) {
            val deps = AccessLevel.parse(item.dependenceJson)
            return "tool '$itemId' requires ${deps.joinToString("+")}, current permission level is ${level.id}"
        }
        return null
    }

    fun audit(action: Action, level: AccessLevel, result: String) {
        println("[ActionGate] level=${level.id} action=${action.type} result=$result")
    }
}
