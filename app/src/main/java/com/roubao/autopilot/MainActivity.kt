package com.roubao.autopilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.provider.Settings
import com.roubao.autopilot.agent.AccessLevelManager
import com.roubao.autopilot.agent.MobileAgent
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.*
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.ui.screens.*
import com.roubao.autopilot.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.roubao.autopilot.vlm.GUIOwlClient
import com.roubao.autopilot.vlm.MAIUIClient
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import android.util.Log

private const val TAG = "MainActivity"

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Home : Screen("home", "肉包", Icons.Outlined.Home, Icons.Filled.Home)
    object Capabilities : Screen("capabilities", "能力", Icons.Outlined.Star, Icons.Filled.Star)
    object History : Screen("history", "记录", Icons.Outlined.List, Icons.Filled.List)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: DeviceController
    private lateinit var settingsManager: SettingsManager
    private lateinit var executionRepository: ExecutionRepository

    private val mobileAgent = mutableStateOf<MobileAgent?>(null)
    private var shizukuAvailable = mutableStateOf(false)

    // 当前执行的协程 Job（用于停止任务）
    private var currentExecutionJob: kotlinx.coroutines.Job? = null
    private var activeWakeLock: PowerManager.WakeLock? = null

    // 执行记录列表
    private val executionRecords = mutableStateOf<List<ExecutionRecord>>(emptyList())

    // 是否正在执行（点击发送后立即为 true）
    private val isExecuting = mutableStateOf(false)

    // 定时任务通知点开后预填的 prompt
    private val scheduledPrompt = mutableStateOf<String?>(null)
    private val scheduledAutoRun = mutableStateOf(false)

    // 悬浮窗输入的新任务：不依赖当前页面/Compose 的 HomeScreen，直接交给 runAgent
    private val pendingOverlayCommand = mutableStateOf<String?>(null)

    // 当前执行的记录 ID（用于停止后跳转）
    private val currentRecordId = mutableStateOf<String?>(null)

    // 是否需要跳转到记录详情（悬浮窗停止后触发）
    private val shouldNavigateToRecord = mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            Log.d(TAG, "Shizuku permission granted, binding service")
            deviceController.bindService()
        } else {
            Log.d(TAG, "Shizuku permission not granted")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        shizukuAvailable.value = false
        AccessLevelManager.refresh(this)
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        Log.d(TAG, "Shizuku permission result: $grantResult")
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deviceController.bindService()
            Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        readScheduledIntent(intent)
        applyOverlayIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        // 设置边到边显示，深色状态栏和导航栏
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)
        settingsManager = App.getInstance().settingsManager
        executionRepository = ExecutionRepository(this)

        // 加载执行记录；上次进程被杀留下的 RUNNING 记录先收敛为 STOPPED
        lifecycleScope.launch {
            executionRepository.markStaleRunningAsStopped()
            executionRecords.value = executionRepository.getAllRecords()
        }

        // 添加 Shizuku 监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 检查 Shizuku 状态
        checkAndUpdateShizukuStatus()

        // 预加载已安装应用
        lifecycleScope.launch(Dispatchers.IO) {
            AppScanner(this@MainActivity).getApps()
        }

        // 非工作状态：桌面常驻肉包图标
        if (Settings.canDrawOverlays(this)) {
            OverlayService.showIdle(this)
        }

        setContent {
            val settings by settingsManager.settings.collectAsState()
            BaoziTheme(themeMode = settings.themeMode) {
                val colors = BaoziTheme.colors
                // 动态更新系统栏颜色
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.backgroundCard.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !colors.isDark
                        isAppearanceLightNavigationBars = !colors.isDark
                    }
                }

                // 首次启动显示引导画面
                if (!settings.hasSeenOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            // 引导去开无障碍时，设置里的无障碍模式默认就是开的。
                            settingsManager.updateAccessibilityModeEnabled(true)
                            settingsManager.setOnboardingSeen()
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
        var selectedRecord by remember { mutableStateOf<ExecutionRecord?>(null) }
        var showShizukuHelpDialog by remember { mutableStateOf(false) }
        var hasShownShizukuHelp by remember { mutableStateOf(false) }

        val settings by settingsManager.settings.collectAsState()
        val colors = BaoziTheme.colors

        LaunchedEffect(pendingOverlayCommand.value) {
            val command = pendingOverlayCommand.value ?: return@LaunchedEffect
            pendingOverlayCommand.value = null
            currentScreen = Screen.Home
            runAgent(
                instruction = command,
                apiKey = settings.apiKey,
                baseUrl = settings.baseUrl,
                model = settings.model,
                maxSteps = settings.maxSteps,
                isGUIAgent = settings.currentProvider.isGUIAgent,
                providerId = settings.currentProviderId,
                source = "overlay"
            )
        }
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        var pausedModelApplied by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(
            settings.currentProviderId,
            settings.model,
            settings.baseUrl,
            settings.apiKey
        ) {
            // 只在“暂停期间用户真的改了模型/服务商”时替换客户端。
            // 进入暂停本身不应该触发“已切换模型客户端”。
            if (agentState?.isPaused == true) {
                val key = "${settings.currentProviderId}|${settings.baseUrl}|${settings.apiKey}|${settings.model}"
                if (pausedModelApplied != key && !settings.currentProvider.isGUIAgent && settings.currentProviderId != "mai_ui") {
                    val client = VLMClient(
                        apiKey = settings.apiKey,
                        baseUrl = settings.baseUrl,
                        model = settings.model
                    )
                    mobileAgent.value?.updateVlmClient(client)
                    pausedModelApplied = key
                }
            }
        }

        LaunchedEffect(agentState?.isPaused) {
            if (agentState?.isPaused != true) {
                pausedModelApplied = null
            }
        }
        val logs by agent?.logs?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()
        val executing by remember { isExecuting }
        val navigateToRecord by remember { shouldNavigateToRecord }
        val recordId by remember { currentRecordId }

        // 监听跳转事件
        LaunchedEffect(navigateToRecord, recordId) {
            if (navigateToRecord && recordId != null) {
                // 找到对应的记录并跳转
                val record = records.find { it.id == recordId }
                if (record != null) {
                    selectedRecord = record
                    currentScreen = Screen.History
                }
                shouldNavigateToRecord.value = false
            }
        }

        // 不再自动弹 Shizuku 使用指南：basic/无障碍模式不需要 Shizuku。
        // 需要时从“设置 → Shizuku 连接 / 授权”手动发起。

        Scaffold(
            modifier = Modifier.background(colors.background),
            containerColor = colors.background,
            bottomBar = {
                if (selectedRecord == null) {
                    NavigationBar(
                        containerColor = colors.background,
                        contentColor = colors.textPrimary,
                        tonalElevation = 0.dp
                    ) {
                        listOf(Screen.Home, Screen.Capabilities, Screen.History, Screen.Settings).forEach { screen ->
                            val selected = currentScreen == screen
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                                        contentDescription = screen.title
                                    )
                                },
                                label = { Text(screen.title) },
                                selected = selected,
                                onClick = { currentScreen = screen },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = if (colors.isDark) colors.textPrimary else Color.White,
                                    selectedTextColor = colors.primary,
                                    unselectedIconColor = colors.textSecondary,
                                    unselectedTextColor = colors.textSecondary,
                                    indicatorColor = colors.primary
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 处理系统返回手势
                BackHandler(enabled = selectedRecord != null) {
                    selectedRecord = null
                }

                // 详情页优先显示
                if (selectedRecord != null) {
                    HistoryDetailScreen(
                        record = selectedRecord!!,
                        onBack = { selectedRecord = null }
                    )
                } else {
                    // 主页面切换
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> {
                                // 每次进入首页都检测 Shizuku 状态
                                LaunchedEffect(Unit) {
                                    checkAndUpdateShizukuStatus()
                                }
                                HomeScreen(
                                    agentState = agentState,
                                    logs = logs,
                                    onExecute = { instruction ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.apiKey,
                                            baseUrl = settings.baseUrl,
                                            model = settings.model,
                                            maxSteps = settings.maxSteps,
                                            isGUIAgent = settings.currentProvider.isGUIAgent,
                                            providerId = settings.currentProviderId,
                                            source = "home"
                                        )
                                    },
                                    onScheduledExecute = { instruction ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.apiKey,
                                            baseUrl = settings.baseUrl,
                                            model = settings.model,
                                            maxSteps = settings.maxSteps,
                                            isGUIAgent = settings.currentProvider.isGUIAgent,
                                            providerId = settings.currentProviderId,
                                            source = "scheduled"
                                        )
                                    },
                                    onInitialConsumed = {
                                        scheduledPrompt.value = null
                                        scheduledAutoRun.value = false
                                    },
                                    onStop = {
                                        mobileAgent.value?.stop()
                                    },
                                    shizukuAvailable = isShizukuAvailable,
                                    currentModel = settings.model,
                                    onRefreshShizuku = { refreshShizukuStatus() },
                                    onShizukuRequired = { showShizukuHelpDialog = true },
                                    isExecuting = executing,
                                    isPaused = agentState?.isPaused == true,
                                    onTogglePause = { mobileAgent.value?.togglePause() },
                                    initialPrompt = scheduledPrompt.value,
                                    initialAutoRun = scheduledAutoRun.value
                                )
                            }
                            Screen.Capabilities -> CapabilitiesScreen()
                            Screen.History -> HistoryScreen(
                                records = records,
                                onRecordClick = { record -> selectedRecord = record },
                                onDeleteRecord = { id -> deleteRecord(id) }
                            )
                            Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onUpdateApiKey = { settingsManager.updateApiKey(it) },
                                onUpdateBaseUrl = { settingsManager.updateBaseUrl(it) },
                                onUpdateModel = { settingsManager.updateModel(it) },
                                onUpdateCachedModels = { settingsManager.updateCachedModels(it) },
                                onUpdateThemeMode = { settingsManager.updateThemeMode(it) },
                                onUpdateMaxSteps = { settingsManager.updateMaxSteps(it) },
                                onUpdateOverlayScale = {
                                    settingsManager.updateOverlayScale(it)
                                    OverlayService.setScale(it)
                                },
                                onUpdateScreenshotMaxLongEdge = { px ->
                                    settingsManager.updateScreenshotMaxLongEdge(px)
                                },
                                onUpdateDebugEnabled = { enabled ->
                                    settingsManager.updateDebugEnabled(enabled)
                                },
                                onUpdateCloudCrashReport = { enabled ->
                                    settingsManager.updateCloudCrashReportEnabled(enabled)
                                    App.getInstance().updateCloudCrashReportEnabled(enabled)
                                },
                                onUpdateRootModeEnabled = { enabled ->
                                    settingsManager.updateRootModeEnabled(enabled)
                                    // 高级模式开关与 Root 权限开关保持同步，等级才会真的切到 advanced
                                    settingsManager.updateAdvancedModeEnabled(enabled)
                                    if (enabled) {
                                        AccessLevelManager.ensureRoot(this@MainActivity)
                                    } else {
                                        AccessLevelManager.invalidateSuCache()
                                        AccessLevelManager.refresh(this@MainActivity)
                                    }
                                },
                                onUpdateSuCommandEnabled = { settingsManager.updateSuCommandEnabled(it) },
                                onUpdateShizukuModeEnabled = { enabled ->
                                    settingsManager.updateShizukuModeEnabled(enabled)
                                    AccessLevelManager.invalidateSuCache()
                                    AccessLevelManager.refresh(this@MainActivity)
                                    refreshShizukuStatus()
                                },
                                onUpdateFileAccessRoot = { path ->
                                    settingsManager.updateFileAccessRoot(path)
                                },
                                onUpdateAccessibilityModeEnabled = {
                                    settingsManager.updateAccessibilityModeEnabled(it)
                                    AccessLevelManager.refresh(this@MainActivity)
                                },
                                onUpdatePromptPackMode = { settingsManager.updatePromptPackMode(it) },
                                onUpdateRootShellConfirmMode = { settingsManager.updateRootShellConfirmMode(it) },
                                onUpdateAdvancedModeEnabled = { enabled ->
                                    settingsManager.updateAdvancedModeEnabled(enabled)
                                    if (enabled) {
                                        AccessLevelManager.ensureRoot(this@MainActivity)
                                    } else {
                                        AccessLevelManager.invalidateSuCache()
                                        AccessLevelManager.refresh(this@MainActivity)
                                    }
                                },
                                onRefreshShizuku = { refreshShizukuStatus() },
                                onSelectProvider = { settingsManager.selectProvider(it) },
                                shizukuAvailable = isShizukuAvailable,
                                shizukuPrivilegeLevel = if (isShizukuAvailable) {
                                    when (deviceController.getShizukuPrivilegeLevel()) {
                                        DeviceController.ShizukuPrivilegeLevel.ROOT -> "ROOT"
                                        DeviceController.ShizukuPrivilegeLevel.ADB -> "ADB"
                                        else -> "NONE"
                                    }
                                } else "NONE",
                                isExecuting = executing,
                                isPaused = agentState?.isPaused == true,
                                onFetchModels = { onSuccess, onError ->
                                    lifecycleScope.launch {
                                        val result = VLMClient.fetchModels(settings.baseUrl, settings.apiKey)
                                        result.onSuccess { models ->
                                            onSuccess(models)
                                        }.onFailure { error ->
                                            onError(error.message ?: "未知错误")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Shizuku 帮助对话框
        if (showShizukuHelpDialog) {
            ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
        }
    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            executionRepository.deleteRecord(id)
            executionRecords.value = executionRepository.getAllRecords()
        }
    }

    private fun acquireTaskWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roubao:task")
            lock.setReferenceCounted(false)
            lock.acquire(30 * 60 * 1000L)
            activeWakeLock = lock
            Log.d(TAG, "task wake lock acquired")
            lock
        } catch (e: Exception) {
            Log.e(TAG, "acquire wake lock failed", e)
            null
        }
    }

    private fun releaseTaskWakeLock(lock: PowerManager.WakeLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (e: Exception) {
            Log.e(TAG, "release wake lock failed", e)
        }
        if (activeWakeLock === lock) activeWakeLock = null
    }

    override fun onDestroy() {
        // 用户退出/划掉 App：取消任务并收起悬浮窗，避免残留“执行中”。
        if (isFinishing) {
            currentExecutionJob?.cancel()
            mobileAgent.value?.stop()
            releaseTaskWakeLock(activeWakeLock)
            OverlayService.idle(this)
            isExecuting.value = false
        }
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "checkShizukuPermission: $granted")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "checkShizukuPermission error", e)
            false
        }
    }

    private fun checkAndUpdateShizukuStatus(requestIfNeeded: Boolean = false) {
        Log.d(TAG, "checkAndUpdateShizukuStatus called, requestIfNeeded=$requestIfNeeded")
        try {
            val binderAlive = Shizuku.pingBinder()
            Log.d(TAG, "Shizuku pingBinder: $binderAlive")

            if (binderAlive) {
                val hasPermission = checkShizukuPermission()
                Log.d(TAG, "Shizuku hasPermission: $hasPermission")

                if (hasPermission) {
                    shizukuAvailable.value = true
                    Log.d(TAG, "Binding Shizuku service")
                    deviceController.bindService()
                } else {
                    // 不自动弹 Shizuku 授权：basic/无障碍模式不需要 Shizuku。
                    // 只有用户主动点“授权/刷新”时才 requestPermission。
                    shizukuAvailable.value = false
                    if (requestIfNeeded) {
                        Log.d(TAG, "Requesting Shizuku permission by user action")
                        requestShizukuPermission()
                    } else {
                        Log.d(TAG, "Shizuku permission missing; skip auto request")
                    }
                }
            } else {
                Log.d(TAG, "Shizuku binder not alive")
                shizukuAvailable.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAndUpdateShizukuStatus error", e)
            shizukuAvailable.value = false
        }
    }

    private fun refreshShizukuStatus() {
        Log.d(TAG, "refreshShizukuStatus called by user")
        Toast.makeText(this, "正在检查 Shizuku 状态...", Toast.LENGTH_SHORT).show()
        checkAndUpdateShizukuStatus(requestIfNeeded = true)

        if (shizukuAvailable.value && checkShizukuPermission()) {
            Toast.makeText(this, "Shizuku 已连接", Toast.LENGTH_SHORT).show()
        } else if (shizukuAvailable.value) {
            Toast.makeText(this, "请在弹窗中授权 Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
                shizukuAvailable.value = true
                deviceController.bindService()
                return
            }

            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAgent(
        instruction: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        maxSteps: Int,
        isGUIAgent: Boolean = false,
        providerId: String = "",
        source: String = "home"
    ) {
        if (instruction.isBlank()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(TAG, "runAgent source=$source instruction=${instruction.take(80)}")
        // MAI-UI 本地部署不需要 API Key
        val requiresApiKey = providerId != "mai_ui"
        if (requiresApiKey && apiKey.isBlank()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 立即设置执行状态为 true，显示停止按钮
        isExecuting.value = true

        val settings = settingsManager.settings.value
        val accessibilityEnabled = settings.accessibilityModeEnabled

        // 根据服务商类型创建相应的客户端
        if (isGUIAgent) {
            // GUI-Owl 模式
            val guiOwlClient = GUIOwlClient(
                apiKey = apiKey,
                model = model.ifBlank { "pre-gui_owl_7b" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = null,
                controller = deviceController,
                context = this,
                guiOwlClient = guiOwlClient,
                accessibilityModeEnabled = accessibilityEnabled
            )
        } else if (providerId == "mai_ui") {
            // MAI-UI 模式
            val maiuiClient = MAIUIClient(
                baseUrl = baseUrl.ifBlank { "http://localhost:8000/v1" },
                model = model.ifBlank { "MAI-UI-2B" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = null,
                controller = deviceController,
                context = this,
                maiuiClient = maiuiClient,
                accessibilityModeEnabled = accessibilityEnabled
            )
        } else {
            // OpenAI 兼容模式 (阿里云 Qwen-VL, OpenAI, OpenRouter 等)
            val vlmClient = VLMClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model.ifBlank { "qwen3-vl-plus" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = vlmClient,
                controller = deviceController,
                context = this,
                accessibilityModeEnabled = accessibilityEnabled
            )
        }

        // 设置停止回调，用于取消协程
        mobileAgent.value?.onStopRequested = {
            currentExecutionJob?.cancel()
            currentExecutionJob = null
        }

        // 创建执行记录
        val record = ExecutionRecord(
            title = generateTitle(instruction),
            instruction = instruction,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )

        // 保存当前记录 ID，用于停止后跳转
        currentRecordId.value = record.id

        // 取消之前的任务（如果有）
        currentExecutionJob?.cancel()

        // 执行期间持有 partial wake lock，避免灭屏后 CPU/网络被挂起。
        val taskWakeLock = acquireTaskWakeLock()

        currentExecutionJob = lifecycleScope.launch {
            // 保存初始记录
            executionRepository.saveRecord(record)
            executionRecords.value = executionRepository.getAllRecords()

            try {
                val result = mobileAgent.value!!.runInstruction(instruction, maxSteps, source = source)

                // 更新记录状态
                val agentState = mobileAgent.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()
                val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = if (result.success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
                    steps = steps,
                    logs = currentLogs,
                    resultMessage = result.message
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()

                // 重置执行状态
                isExecuting.value = false

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消任务 - 使用 NonCancellable 确保清理操作完成
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val agentState = mobileAgent.value?.state?.value
                    val steps = agentState?.executionSteps ?: emptyList()
                    val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()

                    println("[MainActivity] 取消任务 - steps: ${steps.size}, logs: ${currentLogs.size}")

                    val updatedRecord = record.copy(
                        endTime = System.currentTimeMillis(),
                        status = ExecutionStatus.STOPPED,
                        steps = steps,
                        logs = currentLogs,
                        resultMessage = "已取消"
                    )
                    executionRepository.saveRecord(updatedRecord)
                    executionRecords.value = executionRepository.getAllRecords()

                    // 重置执行状态
                    isExecuting.value = false

                    Toast.makeText(this@MainActivity, "任务已停止", Toast.LENGTH_SHORT).show()
                    mobileAgent.value?.clearLogs()

                    // 触发跳转到记录详情页
                    shouldNavigateToRecord.value = true
                }
            } catch (e: Exception) {
                // 更新失败记录
                val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()
                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.FAILED,
                    logs = currentLogs,
                    resultMessage = "错误: ${e.message}"
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                // 重置执行状态
                isExecuting.value = false

                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            } finally {
                releaseTaskWakeLock(taskWakeLock)
            }
        }
    }

    private fun generateTitle(instruction: String): String {
        // 生成简短标题
        val keywords = listOf(
            "打开" to "打开应用",
            "点" to "点餐",
            "发" to "发送消息",
            "看" to "浏览内容",
            "搜" to "搜索",
            "设置" to "调整设置",
            "播放" to "播放媒体"
        )
        for ((key, title) in keywords) {
            if (instruction.contains(key)) {
                return title
            }
        }
        return if (instruction.length > 10) instruction.take(10) + "..." else instruction
    }

    /**
     * 读取定时任务 Intent 后立即移除 extras。
     * 这样 Activity 被系统重建/从最近任务恢复时，不会再拿同一条旧 prompt 自动执行一次。
     */
    private fun readScheduledIntent(intent: Intent?) {
        scheduledPrompt.value = intent?.getStringExtra(EXTRA_SCHEDULED_PROMPT)
        scheduledAutoRun.value = intent?.getBooleanExtra(EXTRA_SCHEDULED_AUTO_RUN, false) == true
        intent?.removeExtra(EXTRA_SCHEDULED_PROMPT)
        intent?.removeExtra(EXTRA_SCHEDULED_AUTO_RUN)
    }

    private fun applyOverlayIntent(intent: Intent?) {
        val command = intent?.getStringExtra("overlay_prompt") ?: return
        if (command.isBlank()) return
        val hideAfterStart = intent.getBooleanExtra("hide_after_start", false)
        intent.removeExtra("overlay_prompt")
        intent.removeExtra("hide_after_start")
        // 不经过 HomeScreen 的 initialAutoRun，避免当前不在首页时丢任务。
        pendingOverlayCommand.value = command
        if (hideAfterStart) {
            // 任务记录已经创建后再退到后台；给 runAgent 留出启动时间。
            window.decorView.postDelayed({ moveTaskToBack(true) }, 3000)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        readScheduledIntent(intent)
        applyOverlayIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AccessLevelManager.refresh(this)
    }

    companion object {
        const val EXTRA_SCHEDULED_PROMPT = "scheduled_prompt"
        const val EXTRA_SCHEDULED_AUTO_RUN = "scheduled_auto_run"
    }
}
