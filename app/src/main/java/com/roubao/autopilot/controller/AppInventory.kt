package com.roubao.autopilot.controller

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.roubao.autopilot.agent.AccessLevel
import com.roubao.autopilot.data.PromptRepository
import org.json.JSONArray

/**
 * 运行时可用的 App 清单。
 *
 * 目标：不要把全部已安装应用（本机 554 个）塞进每次 prompt。
 *
 * - 常驻清单 = 系统默认应用（RoleManager / 标准 Intent 解析）+ 当前等级可用的 Skill 关联应用；
 * - 完整列表不注入，由 Agent 在需要时调用 search_apps 查询。
 *
 * 这样既保留“系统默认/用户常用”的上下文，又不会因为 take(50) 按名字截断而漏掉微信、百度网盘这类应用。
 */
class AppInventory(
    private val context: Context,
    private val appScanner: AppScanner
) {

    data class Entry(
        val packageName: String,
        val appName: String,
        val source: String,
        val isSystem: Boolean
    )

    /**
     * 生成注入 prompt 的常驻清单。只返回“默认应用 + Skill 关联且已安装”的应用。
     */
    fun brief(level: AccessLevel, limit: Int = 60): String {
        val index = try {
            appScanner.getApps().associateBy { it.packageName.lowercase() }
        } catch (e: Exception) {
            emptyMap()
        }

        val entries = LinkedHashMap<String, Entry>()
        defaultPackages().forEach { pkg ->
            entryFor(pkg, "system", index)?.let { entry ->
                val key = entry.packageName.lowercase()
                if (!entries.containsKey(key)) entries[key] = entry
            }
        }
        skillPackages(level).forEach { pkg ->
            entryFor(pkg, "skill", index)?.let { entry ->
                val key = entry.packageName.lowercase()
                if (!entries.containsKey(key)) entries[key] = entry
            }
        }

        if (entries.isEmpty()) return ""
        val list = entries.values.toList()
        val shown = list.take(limit)

        return buildString {
            shown.forEach { entry ->
                append("- ").append(entry.appName).append(" (").append(entry.packageName).append(")\n")
            }
            if (list.size > shown.size) {
                append("- ... ").append(list.size - shown.size).append(" more; call search_apps to find them\n")
            }
        }.trimEnd()
    }

    /**
     * 按需查询全量已安装应用（包括系统应用）。返回名称 + 包名，供模型决定 open_app。
     */
    fun search(query: String, limit: Int = 8): List<Entry> {
        if (query.isBlank()) return emptyList()
        var results = try {
            appScanner.searchApps(query, topK = limit, includeSystem = true)
        } catch (e: Exception) {
            emptyList()
        }
        if (results.isEmpty()) {
            // 可能是刚安装/缓存过期：强制刷新一次再查。
            try {
                appScanner.refreshApps()
                results = appScanner.searchApps(query, topK = limit, includeSystem = true)
            } catch (e: Exception) {
                results = emptyList()
            }
        }
        return results.map {
            Entry(
                packageName = it.app.packageName,
                appName = it.app.appName,
                source = "search",
                isSystem = it.app.isSystem
            )
        }
    }

    // ---------------------------------------------------------------------
    // 默认应用
    // ---------------------------------------------------------------------

    private fun defaultPackages(): List<String> {
        val out = LinkedHashSet<String>()

        // 标准 Intent 解析系统默认项：浏览器/电话/短信/桌面/相册/相机/邮件/音乐/日历/设置/文件/助手。
        // 不用 RoleManager.getRoleHolders：它在公开 SDK 里不可见（隐藏 API），用 Intent 更稳。
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            Intent(Intent.ACTION_VIEW).setType("image/*"),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
            Intent(Intent.ACTION_VIEW).setType("audio/*"),
            Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event"),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"),
            Intent(Intent.ACTION_ASSIST)
        )
        intents.forEach { intent ->
            try {
                val pkg = context.packageManager
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
                    ?.packageName
                // 没有默认应用时 resolveActivity 会返回 Chooser/Resolver，别把系统 UI 当应用注入。
                if (!pkg.isNullOrBlank() &&
                    pkg != "android" &&
                    !pkg.contains("resolver", ignoreCase = true) &&
                    !pkg.contains("intentresolver", ignoreCase = true)
                ) {
                    out.add(pkg)
                }
            } catch (_: Exception) {
            }
        }

        return out.toList()
    }

    private fun skillPackages(level: AccessLevel): List<String> {
        val out = LinkedHashSet<String>()
        try {
            PromptRepository.getInstance().getAllToolSkillPrompts()
                .filter {
                    it.category == "skill" && it.enabled &&
                        AccessLevel.satisfies(level, it.dependenceJson)
                }
                .forEach { item ->
                    val related = try {
                        JSONArray(item.relatedAppsJson.ifBlank { "[]" })
                    } catch (e: Exception) {
                        JSONArray()
                    }
                    for (i in 0 until related.length()) {
                        val obj = related.optJSONObject(i) ?: continue
                        val pkg = obj.optString("package").trim()
                        if (pkg.isNotEmpty()) out.add(pkg)
                    }
                }
        } catch (e: Exception) {
            println("[AppInventory] read skill related apps failed: ${e.message}")
        }
        return out.toList()
    }

    private fun entryFor(
        pkg: String,
        source: String,
        index: Map<String, AppScanner.AppInfo>
    ): Entry? {
        if (pkg.isBlank()) return null
        index[pkg.lowercase()]?.let { app ->
            return Entry(
                packageName = app.packageName,
                appName = app.appName,
                source = source,
                isSystem = app.isSystem
            )
        }
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(ai).toString()
            Entry(
                packageName = pkg,
                appName = label,
                source = source,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (e: Exception) {
            null
        }
    }
}
