package com.roubao.autopilot.agent

import android.content.Context
import com.roubao.autopilot.App
import com.roubao.autopilot.service.AutoPilotAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 当前访问等级。
 *
 * - user：无障碍可用（所有受支持设备的基线）
 * - adb：Shizuku ADB 或 su-user（su 2000 -c）
 * - root：su / Shizuku Root，且高级模式已开启
 *
 * 等级只是“模型能看到什么”的提示；真正执行还要 provider + ActionGate。
 */
object AccessLevelManager {

    private val _level = MutableStateFlow(AccessLevel.USER)
    val level: StateFlow<AccessLevel> = _level

    private val lock = Any()

    @Volatile private var suUserAvailable: Boolean? = null
    @Volatile private var rootAvailable: Boolean? = null
    @Volatile private var lastDetectionAt: Long = 0L

    fun current(): AccessLevel = _level.value

    fun isSuUserAvailable(): Boolean = suUserAvailable == true

    fun isRootAvailable(): Boolean = rootAvailable == true

    /**
     * 同步刷新：只看已缓存的 Shizuku / su 结果，不触发新的 su 弹窗。
     */
    fun refresh(context: Context? = null) {
        val settings = try {
            App.getInstance().settingsManager.settings.value
        } catch (e: Exception) {
            null
        }

        val accessibility = try {
            AutoPilotAccessibilityService.isServiceRunning() ||
                AutoPilotAccessibilityService.getInstance() != null
        } catch (e: Exception) {
            false
        }

        val shizukuUid = shizukuUid()
        val rootAllowed = settings?.advancedModeEnabled == true

        val next = when {
            accessibility.not() -> AccessLevel.USER
            shizukuUid == 0 && rootAllowed -> AccessLevel.ROOT
            shizukuUid == 0 -> AccessLevel.USER // Shizuku Root 但未开高级模式：没有 user shell
            shizukuUid == 2000 -> AccessLevel.ADB
            rootAvailable == true && rootAllowed -> AccessLevel.ROOT
            suUserAvailable == true -> AccessLevel.ADB
            else -> AccessLevel.USER
        }

        update(next)
        println("[AccessLevel] accessibility=$accessibility shizukuUid=$shizukuUid suUser=$suUserAvailable root=$rootAvailable advanced=$rootAllowed -> ${next.id}")
    }

    /**
     * 打开 standard 时调用：检测一次 su-user 并缓存，避免每次启动弹 su。
     */
    fun ensureSuUser(context: Context) {
        if (suUserAvailable != null) return
        if (shizukuUid() == 2000) {
            suUserAvailable = false
            refresh(context)
            return
        }
        detectAsync(context, detectRoot = false)
    }

    /**
     * 打开高级模式时调用：检测 su / Shizuku Root，并刷新等级。
     */
    fun ensureRoot(context: Context) {
        detectAsync(context, detectRoot = true)
    }

    fun invalidateSuCache() {
        synchronized(lock) {
            suUserAvailable = null
            rootAvailable = null
            lastDetectionAt = 0L
        }
    }

    private fun detectAsync(context: Context, detectRoot: Boolean) {
        Thread {
            synchronized(lock) {
                if (detectRoot) {
                    rootAvailable = runSuAsRoot()
                    if (rootAvailable == true) {
                        suUserAvailable = true
                    }
                }
                if (suUserAvailable == null) {
                    suUserAvailable = runSuAsUser()
                }
                lastDetectionAt = System.currentTimeMillis()
            }
            refresh(context)
        }.start()
    }

    private fun update(next: AccessLevel) {
        if (_level.value != next) {
            _level.value = next
        }
    }

    private fun shizukuUid(): Int? {
        return try {
            if (!Shizuku.pingBinder()) return null
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
            Shizuku.getUid()
        } catch (e: Exception) {
            null
        }
    }

    private fun runSuAsUser(): Boolean {
        return runCommand(arrayOf("su", "2000", "-c", "id"), expect = "uid=2000")
    }

    private fun runSuAsRoot(): Boolean {
        return runCommand(arrayOf("su", "-c", "id"), expect = "uid=0")
    }

    private fun runCommand(command: Array<String>, expect: String): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(command)
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            if (process.exitValue() != 0) return false
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            output.contains(expect)
        } catch (e: Exception) {
            false
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
