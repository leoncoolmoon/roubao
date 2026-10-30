package com.roubao.autopilot.data

/**
 * 调试开关的运行时镜像。
 *
 * 设置页改完之后写这里，日志/截图等高频路径直接读，避免每次都走 SettingsManager。
 */
object DebugOptions {
    @Volatile
    var enabled: Boolean = false
}
