package com.roubao.autopilot.agent

import android.os.SystemClock

/**
 * 单步耗时记录器。
 *
 * 用法：每步开头 [reset]，在流程各阶段的边界调用 [mark]（自动累计「上一个标记 → 现在」的差值），
 * 该步结束或提前退出时调用 [finish] 打印一行汇总。
 *
 * 只做统计，不参与任何决策；标记缺失时不会报错，只会让相邻阶段合并计时。
 */
class StepTimer {

    private var stepIndex: Int = 0
    private var startedAt: Long = 0L
    private var lastAt: Long = 0L
    private var totalAtFinish: Long = -1L

    /** 按调用顺序保存各阶段耗时；同名标记会累加（例如一步里两次截图）。 */
    private val marks = LinkedHashMap<String, Long>()

    fun reset(step: Int) {
        stepIndex = step
        marks.clear()
        startedAt = SystemClock.elapsedRealtime()
        lastAt = startedAt
        totalAtFinish = -1L
    }

    /** 记录「上一个标记 → 现在」这一段耗时。 */
    fun mark(label: String) {
        if (startedAt == 0L || totalAtFinish >= 0L) return
        val now = SystemClock.elapsedRealtime()
        marks[label] = (marks[label] ?: 0L) + (now - lastAt)
        lastAt = now
    }

    fun totalMs(): Long = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /**
     * 结束本步统计并返回汇总行；重复调用只打印一次内容。
     * 未被任何标记覆盖的时间（悬浮窗开关、日志、状态更新等）会以「其他」计入。
     */
    fun finish(): String {
        if (totalAtFinish < 0L) {
            totalAtFinish = totalMs()
            val marked = marks.values.sum()
            if (totalAtFinish - marked > 20L) {
                marks["其他"] = totalAtFinish - marked
            }
        }
        return summary()
    }

    fun summary(): String {
        val total = if (totalAtFinish >= 0L) totalAtFinish else totalMs()
        val sb = StringBuilder("⏱ Step ").append(stepIndex).append(" 合计 ").append(format(total))
        if (marks.isNotEmpty()) {
            sb.append(" | ")
            marks.forEach { (label, ms) ->
                sb.append(label).append('=').append(format(ms)).append(' ')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun format(ms: Long): String =
        if (ms >= 1000L) String.format(java.util.Locale.US, "%.1fs", ms / 1000.0) else "${ms}ms"
}
