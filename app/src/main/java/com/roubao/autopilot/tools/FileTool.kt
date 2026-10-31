package com.roubao.autopilot.tools

import com.roubao.autopilot.agent.AccessLevel
import com.roubao.autopilot.agent.AccessLevelManager
import com.roubao.autopilot.controller.DeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 授权目录内的文件操作（给模型的 file 工具）。
 *
 * 分级策略：
 * - list / read / mkdir：直接执行
 * - copy：目标不存在时直接执行，覆盖已有文件需要用户批准
 * - move / delete / write（覆盖已有文件）：需要用户批准
 *
 * 所有路径都会归一化后校验必须落在授权目录内，`../` 逃逸直接拒绝。
 * 审批记忆按「操作 + 路径」计，只在本次任务内有效（[resetApprovals]）。
 */
class FileTool(
    private val controller: DeviceController,
    private val rootProvider: () -> String
) {

    private val approved = mutableSetOf<String>()

    fun resetApprovals() {
        approved.clear()
    }

    fun root(): String {
        val raw = rootProvider().trim().trimEnd('/')
        return raw.ifBlank { DEFAULT_ROOT }
    }

    /**
     * 执行一次文件操作。
     * @param confirm 需要批准时的回调，返回 true 表示用户同意
     * @return 给模型看的执行结果（失败以 "Error:" 开头）
     */
    suspend fun execute(
        op: String,
        path: String,
        dest: String? = null,
        content: String? = null,
        confirm: suspend (String) -> Boolean
    ): String = withContext(Dispatchers.IO) {
        val root = root()
        val target = resolve(root, path)
            ?: return@withContext "Error: 路径越界，只允许操作 $root 内的文件：$path"
        val destination = dest?.let { resolve(root, it) }

        if (dest != null && destination == null) {
            return@withContext "Error: 目标路径越界，只允许操作 $root 内的文件：$dest"
        }

        when (op.lowercase()) {
            "list" -> run("ls -la ${q(target)}")

            "read" -> {
                val out = run("cat ${q(target)}")
                if (out.length > 8000) out.take(8000) + "\n...(已截断)" else out
            }

            "mkdir" -> asSuccess(run("mkdir -p ${q(target)}"), "已创建目录 $target")

            "copy" -> {
                if (destination == null) return@withContext "Error: copy 需要 dest 参数"
                if (!exists(target)) return@withContext "Error: 源路径不存在：$target"
                if (exists(destination) &&
                    !askOnce("copy:$destination", confirm, "文件工具要覆盖已有文件：\n$destination\n\n是否允许？")
                ) {
                    return@withContext "Error: 用户拒绝覆盖 $destination"
                }
                asSuccess(run("cp -r ${q(target)} ${q(destination)}"), "已复制：$target → $destination")
            }

            "move" -> {
                if (destination == null) return@withContext "Error: move 需要 dest 参数"
                if (!exists(target)) return@withContext "Error: 源路径不存在：$target"
                if (!askOnce("move:$target>$destination", confirm, "文件工具要移动文件：\n$target\n→ $destination\n\n是否允许？")) {
                    return@withContext "Error: 用户拒绝移动 $target"
                }
                asSuccess(run("mv ${q(target)} ${q(destination)}"), "已移动：$target → $destination")
            }

            "delete" -> {
                if (!exists(target)) return@withContext "Error: 路径不存在：$target"
                if (!askOnce("delete:$target", confirm, "文件工具要删除：\n$target\n\n是否允许？")) {
                    return@withContext "Error: 用户拒绝删除 $target"
                }
                asSuccess(run("rm -rf ${q(target)}"), "已删除 $target")
            }

            "write" -> {
                if (content == null) return@withContext "Error: write 需要 text 参数作为文件内容"
                if (exists(target) &&
                    !askOnce("write:$target", confirm, "文件工具要覆盖文件：\n$target\n\n是否允许？")
                ) {
                    return@withContext "Error: 用户拒绝覆盖 $target"
                }
                val parent = target.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) run("mkdir -p ${q(parent)}")
                val tmp = "$target.roubao.tmp"
                val written = run("printf %s ${q(content)} > ${q(tmp)} && mv ${q(tmp)} ${q(target)}")
                asSuccess(written, "已写入 $target（${content.length} 字符）")
            }

            else -> "Error: 不支持的操作 $op（可用：list / read / mkdir / copy / move / delete / write）"
        }
    }

    /** 需要批准的操作：同一操作+路径在本次任务内只问一次。 */
    private suspend fun askOnce(
        key: String,
        confirm: suspend (String) -> Boolean,
        message: String
    ): Boolean {
        if (key in approved) return true
        val answer = confirm(message)
        if (answer) approved.add(key)
        return answer
    }

    /**
     * 把模型给的相对/绝对路径解析到授权目录内。
     * 返回 null 表示越界。
     */
    private fun resolve(root: String, rawPath: String): String? {
        val path = rawPath.trim()
        if (path.isEmpty()) return null
        val absolute = if (path.startsWith("/")) path else "$root/$path"
        val normalized = try {
            Paths.get(absolute).normalize().toString()
        } catch (e: Exception) {
            return null
        }
        if (normalized == root) return normalized
        return if (normalized.startsWith("$root/")) normalized else null
    }

    private fun exists(path: String): Boolean =
        run("[ -e ${q(path)} ] && echo __exists__ || echo __missing__").contains("__exists__")

    private fun run(command: String): String {
        val viaShell = controller.shell(command)
        if (viaShell.startsWith("Error: Shizuku") &&
            AccessLevelManager.current().rank >= AccessLevel.ROOT.rank
        ) {
            return controller.rootShell(command)
        }
        return viaShell
    }

    private fun asSuccess(output: String, okMessage: String): String {
        val trimmed = output.trim()
        return when {
            trimmed.startsWith("Error") -> trimmed
            trimmed.isEmpty() -> okMessage
            else -> "$okMessage\n$trimmed"
        }
    }

    /** shell 单引号转义。 */
    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val DEFAULT_ROOT = "/sdcard/Download/肉包"
        val OPERATIONS = listOf("list", "read", "mkdir", "copy", "move", "delete", "write")
    }
}
