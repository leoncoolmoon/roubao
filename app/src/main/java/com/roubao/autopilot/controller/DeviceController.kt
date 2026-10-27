package com.roubao.autopilot.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.roubao.autopilot.App
import com.roubao.autopilot.IShellService
import com.roubao.autopilot.service.AutoPilotAccessibilityService
import com.roubao.autopilot.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备控制器 - 通过 Shizuku UserService 执行 shell 命令
 */
class DeviceController(private val context: Context? = null) {

    companion object {
        // 使用 /data/local/tmp，shell 用户有权限访问
        private const val SCREENSHOT_PATH = "/data/local/tmp/autopilot_screen.png"
    }

    private var shellService: IShellService? = null
    private var serviceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.roubao.autopilot",
            ShellService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
            println("[DeviceController] ShellService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            println("[DeviceController] ShellService disconnected")
        }
    }

    /**
     * 绑定 Shizuku UserService
     */
    fun bindService() {
        if (!isShizukuAvailable()) {
            println("[DeviceController] Shizuku not available")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 解绑服务
     */
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查 Shizuku 是否可用
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查服务是否可用
     */
    fun isAvailable(): Boolean {
        return serviceBound && shellService != null
    }

    /**
     * Shizuku 权限级别
     */
    enum class ShizukuPrivilegeLevel {
        NONE,       // 未连接
        ADB,        // ADB 模式 (UID 2000)
        ROOT        // Root 模式 (UID 0)
    }

    /**
     * 获取当前 Shizuku 权限级别
     * UID 0 = root, UID 2000 = shell (ADB)
     */
    fun getShizukuPrivilegeLevel(): ShizukuPrivilegeLevel {
        if (!isAvailable()) {
            return ShizukuPrivilegeLevel.NONE
        }
        return try {
            val uid = Shizuku.getUid()
            println("[DeviceController] Shizuku UID: $uid")
            when (uid) {
                0 -> ShizukuPrivilegeLevel.ROOT
                else -> ShizukuPrivilegeLevel.ADB
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ShizukuPrivilegeLevel.NONE
        }
    }

    /**
     * 执行 shell 命令 (本地，无权限)
     */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 执行 shell 命令 (通过 Shizuku)
     */
    private fun exec(command: String): String {
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            e.printStackTrace()
            execLocal(command)
        }
    }

    /**
     * 公开的 shell 入口，供 Agent 的 shell 动作 / ShellTool 使用。
     * 明确要求 Shizuku ShellService 已连接，避免退化成 App uid 执行后“看起来成功、实际失败”。
     */
    fun shell(command: String): String {
        if (!isAvailable()) {
            return "Error: Shizuku ShellService 未连接，无法执行 shell 命令"
        }
        return exec(command)
    }

    /**
     * root shell：走 su -c，仅在高级模式/root provider 下使用。
     * 每次 spawn 一个 su 进程，超时 20 秒；失败返回明确错误，不做静默降级。
     */
    fun rootShell(command: String): String {
        if (command.isBlank()) return "Error: empty root command"
        // 优先 Shizuku Root：ShellService 本身已经是 uid 0，不需要每次 spawn su。
        try {
            if (isAvailable() && Shizuku.getUid() == 0) {
                return exec(command)
            }
        } catch (_: Exception) {
        }
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "Error: root command timeout"
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            if (process.exitValue() != 0) {
                "Error(${process.exitValue()}): ${output.take(2000)}"
            } else {
                output
            }
        } catch (e: Exception) {
            "Error: root provider unavailable: ${e.message}"
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 点击屏幕
     */
    fun tap(x: Int, y: Int) {
        exec("input tap $x $y")
    }

    /**
     * 长按
     */
    fun longPress(x: Int, y: Int, durationMs: Int = 1000) {
        exec("input swipe $x $y $x $y $durationMs")
    }

    /**
     * 双击
     */
    fun doubleTap(x: Int, y: Int) {
        exec("input tap $x $y && input tap $x $y")
    }

    /**
     * 滑动
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500) {
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * 输入文本 (使用剪贴板方式，支持中文)
     */
    fun type(text: String) {
        // 检查是否包含非 ASCII 字符
        val hasNonAscii = text.any { it.code > 127 }

        if (hasNonAscii) {
            // 中文等使用剪贴板方式
            typeViaClipboard(text)
        } else {
            // 纯英文数字使用 input text
            val escaped = text.replace("'", "'\\''")
            exec("input text '$escaped'")
        }
    }

    /**
     * 通过剪贴板方式输入中文
     * 使用 Android ClipboardManager API 设置剪贴板，然后发送粘贴按键
     */
    private fun typeViaClipboard(text: String) {
        println("[DeviceController] 尝试输入中文: $text")

        // 方法1: 使用 Android 剪贴板 API + 粘贴 (最可靠，不需要额外 App)
        if (clipboardManager != null) {
            try {
                // 使用 CountDownLatch 等待剪贴板设置完成
                val latch = CountDownLatch(1)
                var clipboardSet = false

                // 必须在主线程操作剪贴板
                mainHandler.post {
                    try {
                        val clip = ClipData.newPlainText("baozi_input", text)
                        clipboardManager?.setPrimaryClip(clip)
                        clipboardSet = true
                        println("[DeviceController] ✅ 已设置剪贴板: $text")
                    } catch (e: Exception) {
                        println("[DeviceController] ❌ 设置剪贴板异常: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }

                // 等待剪贴板设置完成 (最多等 1 秒)
                val success = latch.await(1, TimeUnit.SECONDS)
                if (!success) {
                    println("[DeviceController] ❌ 等待剪贴板超时")
                    return
                }

                if (!clipboardSet) {
                    println("[DeviceController] ❌ 剪贴板设置失败")
                    return
                }

                // 稍等一下确保剪贴板生效
                Thread.sleep(200)

                // 发送粘贴按键 (KEYCODE_PASTE = 279)
                exec("input keyevent 279")
                println("[DeviceController] ✅ 已发送粘贴按键")
                return
            } catch (e: Exception) {
                println("[DeviceController] ❌ 剪贴板方式失败: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[DeviceController] ❌ ClipboardManager 为 null，Context 未设置")
        }

        // 方法2: 使用 ADB Keyboard 广播 (备选，需要安装 ADBKeyboard)
        val escaped = text.replace("\"", "\\\"")
        val adbKeyboardResult = exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$escaped\"")
        println("[DeviceController] ADBKeyboard 广播结果: $adbKeyboardResult")

        if (adbKeyboardResult.contains("result=0")) {
            println("[DeviceController] ✅ ADBKeyboard 输入成功")
            return
        }

        // 方法3: 使用 cmd input text (Android 12+ 可能支持 UTF-8)
        println("[DeviceController] 尝试 cmd input text...")
        exec("cmd input text '$text'")
    }

    /**
     * 输入文本 (逐字符，兼容性更好)
     */
    fun typeCharByChar(text: String) {
        text.forEach { char ->
            when {
                char == ' ' -> exec("input text %s")
                char == '\n' -> exec("input keyevent 66")
                char.isLetterOrDigit() && char.code <= 127 -> exec("input text $char")
                char in "-.,!?@'/:;()" -> exec("input text \"$char\"")
                else -> {
                    // 非 ASCII 字符使用广播
                    exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$char\"")
                }
            }
        }
    }

    /**
     * 返回键
     */
    fun back() {
        exec("input keyevent 4")
    }

    /**
     * Home 键
     */
    fun home() {
        exec("input keyevent 3")
    }

    /** user 级 HOME 兜底：直接发 HOME Intent，不需要 Shizuku。 */
    fun homeViaIntent(): Boolean {
        return try {
            val ctx = context ?: App.getInstance()
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            println("[DeviceController] homeViaIntent failed: ${e.message}")
            false
        }
    }

    /**
     * 回车键
     */
    fun enter() {
        exec("input keyevent 66")
    }

    private val keyNameMap = mapOf(
        "ENTER" to "KEYCODE_ENTER",
        "BACK" to "KEYCODE_BACK",
        "HOME" to "KEYCODE_HOME",
        "TAB" to "KEYCODE_TAB",
        "ESC" to "KEYCODE_ESCAPE",
        "ESCAPE" to "KEYCODE_ESCAPE",
        "SPACE" to "KEYCODE_SPACE",
        "DEL" to "KEYCODE_DEL",
        "BACKSPACE" to "KEYCODE_DEL",
        "FORWARD_DEL" to "KEYCODE_FORWARD_DEL",
        "DELETE" to "KEYCODE_FORWARD_DEL",
        "DPAD_UP" to "KEYCODE_DPAD_UP",
        "DPAD_DOWN" to "KEYCODE_DPAD_DOWN",
        "DPAD_LEFT" to "KEYCODE_DPAD_LEFT",
        "DPAD_RIGHT" to "KEYCODE_DPAD_RIGHT",
        "DPAD_CENTER" to "KEYCODE_DPAD_CENTER",
        "PAGE_UP" to "KEYCODE_PAGE_UP",
        "PAGE_DOWN" to "KEYCODE_PAGE_DOWN",
        "MOVE_HOME" to "KEYCODE_MOVE_HOME",
        "MOVE_END" to "KEYCODE_MOVE_END",
        "APP_SWITCH" to "KEYCODE_APP_SWITCH",
        "MENU" to "KEYCODE_MENU"
    )

    /**
     * 发送一个按键：ENTER / TAB / DPAD_DOWN / PAGE_DOWN 等，
     * 也支持 KEYCODE_XXX、单个字母，以及 CTRL+A 这种组合键。
     */
    fun keyEvent(key: String) {
        val normalized = key.trim().uppercase().removePrefix("KEYCODE_")
        if (normalized.isEmpty()) return

        if (normalized.contains("+")) {
            val parts = normalized.split("+").mapNotNull { part ->
                keyNameMap[part] ?: if (part.length == 1) "KEYCODE_$part" else null
            }
            if (parts.isNotEmpty()) {
                exec("input keycombination ${parts.joinToString(" ")}")
            }
            return
        }

        val code = keyNameMap[normalized]
            ?: if (normalized.length == 1) "KEYCODE_$normalized" else "KEYCODE_$normalized"
        exec("input keyevent $code")
    }

    private var cacheDir: File? = null

    fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    /**
     * 截图结果
     */
    data class ScreenshotResult(
        val bitmap: Bitmap,
        val isSensitive: Boolean = false,  // 是否是敏感页面（截图失败）
        val isFallback: Boolean = false    // 是否是降级的黑屏占位图
    )

    /**
     * 截图 - 使用 /data/local/tmp 并设置全局可读权限
     * 失败时返回黑屏占位图（降级处理）
     */
    suspend fun screenshotWithFallback(): ScreenshotResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        // 优先无障碍截图（API 30+）：全屏、无 shell 也能用，basic 包的关键路径。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val service = AutoPilotAccessibilityService.getInstance()
                val bitmap = service?.takeScreenshotBitmap()
                if (bitmap != null) {
                    println("[DeviceController] Screenshot via accessibility takeScreenshot")
                    logShotElapsed(startedAt, "无障碍", bitmap)
                    return@withContext ScreenshotResult(bitmap)
                }
            } catch (e: Exception) {
                println("[DeviceController] Accessibility screenshot failed: ${e.message}")
            }
        }

        try {
            // 截图到 /data/local/tmp 并设置权限让 App 可读
            val output = exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            // 检查是否截图失败（敏感页面保护）
            if (output.contains("Status: -1") || output.contains("Failed") || output.contains("error")) {
                println("[DeviceController] Screenshot blocked (sensitive screen), returning fallback")
                return@withContext createFallbackScreenshot(isSensitive = true)
            }

            // 尝试直接读取
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                println("[DeviceController] Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                val bitmap = BitmapFactory.decodeFile(SCREENSHOT_PATH)
                if (bitmap != null) {
                    logShotElapsed(startedAt, "screencap 文件", bitmap)
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            // 如果无法直接读取，通过 shell cat 读取二进制数据
            println("[DeviceController] Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                println("[DeviceController] Read ${bytes.size} bytes via shell")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    logShotElapsed(startedAt, "shell cat", bitmap)
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            println("[DeviceController] Screenshot file empty or not accessible, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        } catch (e: Exception) {
            e.printStackTrace()
            println("[DeviceController] Screenshot exception, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        }
    }

    /** 打印截图耗时与来源，用于定位每步延迟。 */
    private fun logShotElapsed(startedAt: Long, source: String, bitmap: Bitmap) {
        println(
            "[DeviceController] 截图($source) ${bitmap.width}x${bitmap.height} " +
                "用时 ${System.currentTimeMillis() - startedAt}ms"
        )
    }

    /**
     * 创建黑屏占位图（降级处理）
     */
    private fun createFallbackScreenshot(isSensitive: Boolean): ScreenshotResult {
        val (width, height) = getScreenSize()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 默认是黑色，无需填充
        return ScreenshotResult(
            bitmap = bitmap,
            isSensitive = isSensitive,
            isFallback = true
        )
    }

    /**
     * 截图 - 使用 /data/local/tmp 并设置全局可读权限
     * @deprecated 使用 screenshotWithFallback() 代替
     */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 截图到 /data/local/tmp 并设置权限让 App 可读
            exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            // 尝试直接读取
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                println("[DeviceController] Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                return@withContext BitmapFactory.decodeFile(SCREENSHOT_PATH)
            }

            // 如果无法直接读取，通过 shell cat 读取二进制数据
            println("[DeviceController] Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                println("[DeviceController] Read ${bytes.size} bytes via shell")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                println("[DeviceController] Screenshot file empty or not accessible")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取屏幕尺寸（tap / input 使用的当前 display 坐标空间，含方向）。
     *
     * 不依赖 `wm size`：basic / 无 Shizuku 时 `wm size` 会以 App uid 执行失败，
     * 旧实现会静默回退到 1080x2400，导致换手机后所有坐标偏移。
     */
    fun getScreenSize(): Pair<Int, Int> {
        val ctx = context ?: try {
            App.getInstance()
        } catch (e: Exception) {
            null
        }

        if (ctx != null) {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // maximumWindowMetrics 是完整 display 边界，已按当前旋转方向返回。
                        val bounds = wm.maximumWindowMetrics.bounds
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            println("[DeviceController] screen via maximumWindowMetrics: ${bounds.width()}x${bounds.height()}")
                            return Pair(bounds.width(), bounds.height())
                        }
                    } else {
                        val dm = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealMetrics(dm)
                        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                            println("[DeviceController] screen via getRealMetrics: ${dm.widthPixels}x${dm.heightPixels}")
                            return Pair(dm.widthPixels, dm.heightPixels)
                        }
                    }
                }

                val dm = ctx.resources.displayMetrics
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    println("[DeviceController] screen via resources.displayMetrics: ${dm.widthPixels}x${dm.heightPixels}")
                    return Pair(dm.widthPixels, dm.heightPixels)
                }
            } catch (e: Exception) {
                println("[DeviceController] WindowManager screen size failed: ${e.message}")
            }
        }

        // 最后兜底：解析 wm size；有 Override size 时取最后一个（override 优先）。
        val output = exec("wm size")
        val matches = Regex("(\\d+)x(\\d+)").findAll(output).toList()
        if (matches.isNotEmpty()) {
            val (w, h) = matches.last().destructured
            val width = w.toInt()
            val height = h.toInt()
            val landscape = ctx?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
            println("[DeviceController] screen via wm size: ${width}x${height}, landscape=$landscape")
            return if (landscape) Pair(height, width) else Pair(width, height)
        }

        println("[DeviceController] WARNING: cannot determine screen size, fallback 1080x2400")
        return Pair(1080, 2400)
    }

    /**
     * 打开 App - 支持包名或应用名
     */
    fun openApp(appNameOrPackage: String) {
        // 常见应用名到包名的映射 (作为备选)
        val packageMap = mapOf(
            "settings" to "com.android.settings",
            "设置" to "com.android.settings",
            "chrome" to "com.android.chrome",
            "浏览器" to "com.android.browser",
            "camera" to "com.android.camera",
            "相机" to "com.android.camera",
            "phone" to "com.android.dialer",
            "电话" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "联系人" to "com.android.contacts",
            "messages" to "com.android.mms",
            "短信" to "com.android.mms",
            "gallery" to "com.android.gallery3d",
            "相册" to "com.android.gallery3d",
            "clock" to "com.android.deskclock",
            "时钟" to "com.android.deskclock",
            "calculator" to "com.android.calculator2",
            "计算器" to "com.android.calculator2",
            "calendar" to "com.android.calendar",
            "日历" to "com.android.calendar",
            "files" to "com.android.documentsui",
            "文件" to "com.android.documentsui"
        )

        val lowerName = appNameOrPackage.lowercase().trim()
        val finalPackage: String

        if (appNameOrPackage.contains(".")) {
            // 已经是包名格式
            finalPackage = appNameOrPackage
        } else if (packageMap.containsKey(lowerName)) {
            // 从内置映射中查找
            finalPackage = packageMap[lowerName]!!
        } else {
            // 使用 AppScanner 搜索应用
            val appScanner = App.getInstance().appScanner
            var searchResults = appScanner.searchApps(appNameOrPackage, topK = 1)
            if (searchResults.isEmpty()) {
                // 可能是刚安装/缓存过期：强制刷新一次再找。
                appScanner.refreshApps()
                searchResults = appScanner.searchApps(appNameOrPackage, topK = 1)
            }
            if (searchResults.isNotEmpty()) {
                finalPackage = searchResults[0].app.packageName
                println("[DeviceController] AppScanner found: ${searchResults[0].app.appName} -> $finalPackage")
            } else {
                // 找不到，直接用原始输入尝试
                finalPackage = appNameOrPackage
                println("[DeviceController] App not found in AppScanner: $appNameOrPackage")
            }
        }

        // 优先沿用旧版直接启动方式（monkey），只有 Shizuku 不可用时才用 Intent 兜底。
        // 这样在 adb/root 环境不会触发系统的“是否允许打开”确认。
        if (isAvailable()) {
            val result = exec("monkey -p $finalPackage -c android.intent.category.LAUNCHER 1 2>/dev/null")
            println("[DeviceController] openApp via monkey: $appNameOrPackage -> $finalPackage, result: $result")
            if (!result.contains("No activities", ignoreCase = true) &&
                !result.contains("Error", ignoreCase = true)
            ) {
                return
            }
            println("[DeviceController] monkey failed, fallback to Intent")
        }

        // 兜底：普通 launch Intent，user 级即可启动。
        val launchContext = context ?: App.getInstance()
        try {
            val launchIntent = launchContext.packageManager
                .getLaunchIntentForPackage(finalPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchContext.startActivity(launchIntent)
                println("[DeviceController] openApp via Intent fallback: $appNameOrPackage -> $finalPackage")
                return
            }
            println("[DeviceController] no launch intent for $finalPackage")
        } catch (e: Exception) {
            println("[DeviceController] openApp Intent fallback failed: ${e.message}")
        }
    }

    /**
     * 通过 Intent 打开
     */
    fun openIntent(action: String, data: String? = null) {
        val cmd = buildString {
            append("am start -a $action")
            if (data != null) {
                append(" -d \"$data\"")
            }
        }
        exec(cmd)
    }

    /**
     * 打开 DeepLink
     */
    fun openDeepLink(uri: String) {
        // 优先用普通 Intent，user 级即可打开；失败再回退 shell（需要 adb）。
        try {
            val ctx = context ?: App.getInstance()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            exec("am start -a android.intent.action.VIEW -d \"$uri\"")
        }
    }
}
