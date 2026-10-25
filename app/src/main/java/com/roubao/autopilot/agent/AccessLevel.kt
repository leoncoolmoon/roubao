package com.roubao.autopilot.agent

import org.json.JSONArray

/**
 * 访问等级：user（无障碍） < adb（Shizuku ADB / su-user） < root（su / Shizuku Root）
 *
 * dependence 数组目前只包含等级 id；空数组表示无依赖（所有等级可用）。
 */
enum class AccessLevel(val rank: Int, val id: String, val label: String) {
    USER(0, "user", "无障碍"),
    ADB(1, "adb", "ADB"),
    ROOT(2, "root", "Root");

    companion object {
        fun fromId(id: String?): AccessLevel {
            val safe = id?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == safe } ?: USER
        }

        fun maxOf(dependence: List<String>): AccessLevel =
            dependence.map { fromId(it) }.maxByOrNull { it.rank } ?: USER

        fun maxOfJson(json: String?): AccessLevel = maxOf(parse(json))

        fun parse(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { i -> array.optString(i) }
                    .filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun satisfies(level: AccessLevel, dependenceJson: String?): Boolean =
            maxOfJson(dependenceJson).rank <= level.rank
    }
}
