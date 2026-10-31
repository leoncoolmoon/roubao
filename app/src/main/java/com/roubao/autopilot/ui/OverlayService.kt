package com.roubao.autopilot.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roubao.autopilot.MainActivity
import com.roubao.autopilot.App
import com.roubao.autopilot.R

/**
 * 肉包悬浮窗服务
 *
 * 视觉结构（空闲态与工作态里，包子是同一个 ImageView，位置/大小永远一致）：
 *
 *   空闲态：  [包子]                               窗口 84dp × 84dp
 *   工作态：  [包子]【橙色面板：当前步骤文字】      窗口 360dp × 84dp
 *
 * 层级（从下到上）：橙色面板 → 包子 → 文字 → 操作按钮行 → 输入面板
 *
 * 工作时包子循环播放「横向压缩→还原→纵向压缩→还原」动画；点击包子暂停/继续（暂停时动画冻结），
 * 状态变化用 Toast 提示；长按包子打开主界面。
 *
 * 资源：
 *   R.drawable.roubao_idle_icon   包子图标（drawable-nodpi，已裁成紧凑的正方形）
 *   R.drawable.roubao_panel_bg    橙色圆角面板 9-patch（drawable-xxhdpi，只有面板，没有包子）
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    /** 悬浮窗整体缩放，默认 65%；可在设置中实时调整。 */
    private var uiScale = 0.65f

    private var panelView: View? = null            // 橙色面板（工作态显示）
    private var idleIconView: ImageView? = null    // 包子（始终显示，位置固定）
    private var textView: TextView? = null
    private var actionButton: TextView? = null
    private var actionRowTag: LinearLayout? = null
    private var cancelButton: TextView? = null     // 确认模式下的取消按钮

    private var askUserPanel: LinearLayout? = null
    private var askUserInput: EditText? = null
    private var askUserQuestion: TextView? = null
    private var pendingAskAnswer: ((String) -> Unit)? = null
    private var askUserFromIdle = false

    private var isIdleMode = false
    private var isMinimized = false   // 最小化：只显示包子，隐藏橙色内容区
    private var bunAnimator: ValueAnimator? = null   // 包子的压缩动画

    companion object {
        private var instance: OverlayService? = null
        /** 用户点了「退出应用」：之后任何 idle/show 都不允许再拉起悬浮窗。 */
        @Volatile
        private var shuttingDown = false
        private var stopCallback: (() -> Unit)? = null
        private var pauseCallback: (() -> Unit)? = null
        private var continueCallback: (() -> Unit)? = null
        private var confirmCallback: ((Boolean) -> Unit)? = null  // 敏感操作确认回调
        private var isTakeOverMode = false
        private var isConfirmMode = false  // 敏感操作确认模式
        private var isPaused = false

        // 悬浮窗布局尺寸（dp）。包子的位置/大小只由这几个常量决定，空闲态与工作态共用
        private const val WINDOW_HEIGHT_DP = 84
        private const val IDLE_WINDOW_WIDTH_DP = 84
        private const val WORK_WINDOW_WIDTH_DP = 360
        private const val ASK_WINDOW_HEIGHT_DP = 190
        private const val BUN_SIZE_DP = 76
        private const val BUN_LEFT_DP = 4
        private const val BUN_SQUASH_SCALE = 0.95f              // 压缩到 95%
        private const val BUN_ANIM_CYCLE_MS = 1600L             // 一个完整循环（横向+纵向）时长
        private const val PANEL_HEIGHT_DP = 60
        private const val PANEL_LEFT_DP = 60                    // 面板从这里开始，左端被包子盖住一部分
        private const val PANEL_RIGHT_DP = 4
        private const val TEXT_LEFT_DP = 90

        // 等待 instance 回调队列
        private val pendingCallbacks = mutableListOf<() -> Unit>()

        /** 结束任务后回到桌面肉包图标，不停止服务 */
        fun idle(context: Context) {
            if (shuttingDown) return
            stopCallback = null
            pauseCallback = null
            continueCallback = null
            confirmCallback = null
            isTakeOverMode = false
            isConfirmMode = false
            isPaused = false
            val instance = instance
            if (instance != null) instance.setIdleMode() else showIdle(context)
        }

        /** 非工作状态：桌面上只显示一个可点击的肉包图标 */
        fun showIdle(context: Context) {
            if (shuttingDown) return
            val instance = instance
            if (instance != null) {
                instance.setIdleMode()
            } else {
                val intent = Intent(context, OverlayService::class.java).apply {
                    putExtra("idle", true)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun show(context: Context, text: String, onStop: (() -> Unit)? = null) {
            if (shuttingDown) return
            stopCallback = onStop
            isTakeOverMode = false
            isConfirmMode = false
            isPaused = false
            instance?.updateText(text) ?: run {
                val intent = Intent(context, OverlayService::class.java).apply {
                    putExtra("text", text)
                }
                ContextCompat.startForegroundService(context, intent)
            }
            instance?.setNormalMode()
        }

        fun hide(context: Context) {
            stopCallback = null
            pauseCallback = null
            continueCallback = null
            confirmCallback = null
            isTakeOverMode = false
            isConfirmMode = false
            isPaused = false
            pendingCallbacks.clear()
            // 只有当 service 已经启动完成时才停止它
            // 否则会导致 ForegroundServiceDidNotStartInTimeException
            if (instance != null) {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }

        /** 退出应用时用：彻底关掉悬浮窗，并且挡住之后任何 idle/show 的重新拉起。 */
        fun shutdown(context: Context) {
            shuttingDown = true
            stopCallback = null
            pauseCallback = null
            continueCallback = null
            confirmCallback = null
            isTakeOverMode = false
            isConfirmMode = false
            isPaused = false
            pendingCallbacks.clear()
            instance?.let { svc ->
                try {
                    Handler(Looper.getMainLooper()).post {
                        svc.overlayView?.let { view ->
                            try {
                                svc.windowManager?.removeView(view)
                            } catch (_: Exception) {
                            }
                        }
                        svc.overlayView = null
                        try {
                            svc.stopForeground(true)
                        } catch (_: Exception) {
                        }
                        svc.stopSelf()
                    }
                } catch (_: Exception) {
                }
            }
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** 重新打开 App 时清掉退出标记，否则悬浮窗再也起不来。 */
        fun resetShutdownFlag() {
            shuttingDown = false
        }

        fun update(text: String) {
            instance?.updateText(text)
        }

        fun setPauseCallback(callback: (() -> Unit)?) {
            pauseCallback = callback
        }

        fun updatePauseState(paused: Boolean) {
            val changed = isPaused != paused
            isPaused = paused
            instance?.applyPauseState(paused, changed)
        }

        /** 截图/动作期间临时隐藏悬浮窗 */
        fun setVisible(visible: Boolean) {
            instance?.overlayView?.post {
                instance?.overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }

        /** 设置里调整悬浮窗比例后立即重建。 */
        fun setScale(scale: Float) {
            instance?.applyScale(scale.coerceIn(0.4f, 1.2f))
        }

        /** 显示人机协作模式 - 等待用户手动完成操作 */
        fun showTakeOver(message: String, onContinue: () -> Unit) {
            val action: () -> Unit = {
                println("[OverlayService] showTakeOver: $message")
                continueCallback = onContinue
                isTakeOverMode = true
                isConfirmMode = false
                instance?.setTakeOverMode(message)
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                println("[OverlayService] showTakeOver: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 显示敏感操作确认模式 - 用户确认或取消 */
        fun showConfirm(message: String, onConfirm: (Boolean) -> Unit) {
            val action: () -> Unit = {
                println("[OverlayService] showConfirm: $message")
                confirmCallback = onConfirm
                isConfirmMode = true
                isTakeOverMode = false
                instance?.setConfirmMode(message)
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                println("[OverlayService] showConfirm: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 显示可输入文字的用户补充信息弹窗 */
        fun showAskUser(question: String, onAnswer: (String) -> Unit) {
            val action: () -> Unit = {
                instance?.showAskUserDialog(question, onAnswer)
                Unit
            }
            if (instance != null) {
                action()
            } else {
                pendingCallbacks.add(action)
            }
        }

        /** 超时或取消时关闭补充信息弹窗 */
        fun dismissAskUser() {
            instance?.dismissAskUserDialog()
        }

        /** 当 instance 可用时执行等待中的回调 */
        private fun processPendingCallbacks() {
            println("[OverlayService] processPendingCallbacks: ${pendingCallbacks.size} pending")
            pendingCallbacks.forEach { it.invoke() }
            pendingCallbacks.clear()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        uiScale = try {
            App.getInstance().settingsManager.settings.value.overlayScale.coerceIn(0.4f, 1.2f)
        } catch (e: Exception) {
            0.65f
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 必须第一时间调用 startForeground，否则会崩溃
        startForegroundNotification()

        // 创建悬浮窗（可能因权限问题失败）
        try {
            createOverlayView()
        } catch (e: Exception) {
            println("[OverlayService] createOverlayView failed: ${e.message}")
        }

        // 处理在 service 启动前排队的回调
        processPendingCallbacks()
    }

    private fun startForegroundNotification() {
        val channelId = "baozi_overlay"
        val channelName = "肉包状态"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示肉包执行状态"
                    setShowBadge(false)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("肉包运行中")
                .setContentText("正在执行自动化任务...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(1001, notification)
        } catch (e: Exception) {
            println("[OverlayService] startForegroundNotification error: ${e.message}")
            // 降级：使用最简单的通知确保 startForeground 被调用
            try {
                val fallbackNotification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("肉包")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build()
                startForeground(1001, fallbackNotification)
            } catch (e2: Exception) {
                println("[OverlayService] fallback startForeground also failed: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("idle", false) == true) {
            setIdleMode()
        } else {
            val text = intent?.getStringExtra("text") ?: "AutoPilot"
            updateText(text)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        bunAnimator?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        // 容器本身不设背景：背景由「橙色面板」和「包子」两个独立 View 组成
        val container = FrameLayout(this)

        // ① 橙色面板（最底层）：从包子右侧伸出，左端被包子盖住，像对话框从包子里弹出
        panelView = View(this).apply {
            setBackgroundResource(R.drawable.roubao_panel_bg)
            alpha = 0.92f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(PANEL_HEIGHT_DP),
                Gravity.CENTER_VERTICAL
            ).apply {
                leftMargin = dp(PANEL_LEFT_DP)
                rightMargin = dp(PANEL_RIGHT_DP)
            }
        }
        container.addView(panelView)

        // ② 包子：空闲态与工作态是同一个 View，位置、大小固定，所以必然重合
        idleIconView = ImageView(this).apply {
            setImageResource(R.drawable.roubao_idle_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                dp(BUN_SIZE_DP), dp(BUN_SIZE_DP),
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { leftMargin = dp(BUN_LEFT_DP) }

            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var longPressed = false
            val bunLongHandler = Handler(Looper.getMainLooper())
            val tapHandler = Handler(Looper.getMainLooper())
            var pendingTap: Runnable? = null
            val bunLongRunnable = Runnable {
                if (!moved) {
                    longPressed = true
                    openMainWindow()
                }
            }
            // 包子：拖动移动悬浮窗；长按打开主界面；单击 —— 空闲态打开输入面板，工作态暂停/继续；双击 —— 最小化/展开
            setOnTouchListener { _, event ->
                val p = overlayParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = p.x
                        startY = p.y
                        moved = false
                        longPressed = false
                        bunLongHandler.postDelayed(bunLongRunnable, 500)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                            moved = true
                            bunLongHandler.removeCallbacks(bunLongRunnable)
                        }
                        if (moved) {
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()
                            windowManager?.updateViewLayout(container, p)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        bunLongHandler.removeCallbacks(bunLongRunnable)
                        if (!moved && !longPressed) {
                            when {
                                isIdleMode -> showIdleInput()
                                isTakeOverMode || isConfirmMode -> Unit
                                else -> {
                                    val pending = pendingTap
                                    if (pending != null) {
                                        // 双击：最小化 / 展开
                                        tapHandler.removeCallbacks(pending)
                                        pendingTap = null
                                        pauseCallback?.invoke()
                                    } else {
                                        // 先等一个双击间隔，期间没有第二次点击才算单击（暂停/继续）
                                        val r = Runnable {
                                            pendingTap = null
                                            toggleMinimize()
                                        }
                                        pendingTap = r
                                        tapHandler.postDelayed(r, ViewConfiguration.getDoubleTapTimeout().toLong())
                                    }
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        bunLongHandler.removeCallbacks(bunLongRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
        container.addView(idleIconView)

        // ③ 文字：叠在橙色面板上
        textView = TextView(this).apply {
            text = "准备中..."
            textSize = 13f * uiScale
            setTextColor(Color.parseColor("#3B1A00"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            ).apply {
                leftMargin = dp(TEXT_LEFT_DP)
                rightMargin = dp(12)
            }
        }
        container.addView(textView)

        // ④ 操作按钮行（继续 / 确认 / 取消）
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(10) }
        }
        actionButton = TextView(this).apply {
            text = ""
            textSize = 13f * uiScale
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                when {
                    isConfirmMode -> {
                        confirmCallback?.invoke(true)
                        confirmCallback = null
                        isConfirmMode = false
                        setNormalMode()
                    }
                    isTakeOverMode -> {
                        continueCallback?.invoke()
                        continueCallback = null
                        isTakeOverMode = false
                        setNormalMode()
                    }
                }
            }
        }
        cancelButton = TextView(this).apply {
            text = "取消"
            textSize = 12f * uiScale
            setTextColor(Color.parseColor("#FFD1D1"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setOnClickListener {
                if (isConfirmMode) {
                    confirmCallback?.invoke(false)
                    confirmCallback = null
                    isConfirmMode = false
                    setNormalMode()
                }
            }
        }
        actionRow.addView(actionButton)
        actionRow.addView(cancelButton)
        actionRowTag = actionRow
        container.addView(actionRow)

        // ⑤ ask_user 输入面板：直接显示在悬浮窗内部，避免 Service 弹 Dialog 的 token 问题
        val askPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundResource(R.drawable.roubao_panel_bg)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        askUserQuestion = TextView(this).apply {
            textSize = 12f * uiScale
            setTextColor(Color.parseColor("#3B1A00"))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        askUserInput = EditText(this).apply {
            hint = "输入补充信息"
            textSize = 13f * uiScale
            setSingleLine(true)
            setImeOptions(EditorInfo.IME_ACTION_DONE)
            setTextColor(Color.parseColor("#3B1A00"))
            setHintTextColor(Color.argb(150, 59, 26, 0))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitAskUser()
                    true
                } else false
            }
        }
        val askSubmit = TextView(this).apply {
            text = "提交"
            textSize = 12f * uiScale
            setTextColor(Color.parseColor("#3B1A00"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FFA94D"))
            }
            setOnClickListener { submitAskUser() }
        }
        val askCancel = TextView(this).apply {
            text = "取消"
            textSize = 12f * uiScale
            setTextColor(Color.argb(190, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(55, 255, 255, 255))
            }
            setOnClickListener { cancelAskUser() }
        }
        val askButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        askButtons.addView(askCancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30)
        ).apply { rightMargin = dp(8) })
        askButtons.addView(askSubmit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30)
        ))
        askPanel.addView(askUserQuestion)
        askPanel.addView(askUserInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ).apply { topMargin = dp(4) })
        askPanel.addView(askButtons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(30)
        ).apply { topMargin = dp(6) })
        askUserPanel = askPanel
        container.addView(askPanel)

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  // 保持屏幕常亮
            format = PixelFormat.TRANSLUCENT
            width = dp(WORK_WINDOW_WIDTH_DP)
            height = dp(WINDOW_HEIGHT_DP)
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }
        overlayParams = params

        // 添加拖动功能（只拦截文字区域，不影响按钮点击）
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = dp(8).toFloat()
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (!isDragging) {
                // 长按右侧文字：只打开窗口，不暂停任务
                openMainWindow()
            }
        }

        // 文字区域：拖动移动悬浮窗；长按打开肉包窗口
        textView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressHandler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (kotlin.math.abs(deltaX) > dragThreshold || kotlin.math.abs(deltaY) > dragThreshold) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    isDragging
                }
                else -> false
            }
        }

        overlayView = container
        windowManager?.addView(overlayView, params)
    }

    private fun dp(value: Int): Int = (value * uiScale * resources.displayMetrics.density).toInt()

    private fun applyScale(scale: Float) {
        if (kotlin.math.abs(uiScale - scale) < 0.01f) return
        uiScale = scale
        val wasIdle = isIdleMode
        val oldText = textView?.text?.toString() ?: "准备中..."
        bunAnimator?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        panelView = null
        idleIconView = null
        textView = null
        actionRowTag = null
        actionButton = null
        cancelButton = null
        askUserPanel = null
        askUserQuestion = null
        askUserInput = null
        try {
            createOverlayView()
            if (wasIdle) {
                setIdleMode()
            } else {
                updateText(oldText)
            }
        } catch (e: Exception) {
            println("[OverlayService] applyScale failed: ${e.message}")
        }
    }

    /** 空闲态：只有包子，窗口缩成 84×84 */
    private fun setIdleMode() {
        isIdleMode = true
        isMinimized = false
        overlayView?.post {
            resetBunAnimation()
            // 之前动作执行会用 setVisible(false) 把整窗隐藏；空闲态必须显式恢复，
            // 否则会出现“服务还活着、窗口却看不见”的隐身状态。
            overlayView?.visibility = View.VISIBLE
            panelView?.visibility = View.GONE
            idleIconView?.visibility = View.VISIBLE
            askUserPanel?.visibility = View.GONE
            textView?.visibility = View.GONE
            actionRowTag?.visibility = View.GONE
            val params = overlayParams ?: return@post
            params.width = dp(IDLE_WINDOW_WIDTH_DP)
            params.height = dp(WINDOW_HEIGHT_DP)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(overlayView, params)
        }
    }

    private fun showIdleInput() {
        askUserFromIdle = true
        showAskUserDialog("想让我做什么？") { command ->
            startOverlayCommand(command)
        }
    }

    private fun startOverlayCommand(command: String) {
        if (command.isBlank()) return
        setIdleMode()
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("overlay_prompt", command)
                putExtra("hide_after_start", true)
            })
        } catch (e: Exception) {
            println("[OverlayService] 启动悬浮命令失败: ${e.message}")
        }
    }

    private fun openMainWindow() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) {
            println("[OverlayService] 打开主窗口失败: ${e.message}")
        }
    }

    private fun updateText(text: String) {
        textView?.post {
            textView?.text = text
        }
    }

    fun dismissAskUserDialog() {
        pendingAskAnswer = null
        hideAskUserPanel()
    }

    /** 等待用户输入补充信息：直接在当前悬浮窗内展开输入区，避免 Service Dialog token 问题 */
    private fun showAskUserDialog(question: String, onAnswer: (String) -> Unit) {
        askUserFromIdle = isIdleMode
        isIdleMode = false
        isMinimized = false   // 需要用户输入，自动展开
        pendingAskAnswer = onAnswer
        overlayView?.post {
            // 输入面板自带深色背景并盖住整个窗口，这里把橙色面板和包子一起收起来
            panelView?.visibility = View.GONE
            idleIconView?.visibility = View.GONE
            askUserQuestion?.text = question
            askUserInput?.setText("")
            askUserPanel?.visibility = View.VISIBLE
            textView?.visibility = View.GONE
            actionRowTag?.visibility = View.GONE

            val params = overlayParams ?: return@post
            params.width = dp(WORK_WINDOW_WIDTH_DP)
            params.height = dp(ASK_WINDOW_HEIGHT_DP)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager?.updateViewLayout(overlayView, params)
            askUserInput?.requestFocus()
            askUserInput?.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(askUserInput, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }
    }

    private fun submitAskUser() {
        val answer = askUserInput?.text?.toString().orEmpty()
        val callback = pendingAskAnswer
        pendingAskAnswer = null
        hideAskUserPanel()
        callback?.invoke(answer)
    }

    private fun cancelAskUser() {
        val fromIdle = askUserFromIdle
        val callback = pendingAskAnswer
        pendingAskAnswer = null
        hideAskUserPanel()
        if (!fromIdle) callback?.invoke("")
    }

    private fun hideAskUserPanel() {
        val returnToIdle = askUserFromIdle
        askUserFromIdle = false
        overlayView?.post {
            askUserPanel?.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(askUserInput?.windowToken, 0)
            if (returnToIdle) {
                setIdleMode()
            } else {
                panelView?.visibility = View.VISIBLE
                idleIconView?.visibility = View.VISIBLE
                textView?.visibility = View.VISIBLE
                actionRowTag?.visibility = View.GONE
                val params = overlayParams ?: return@post
                params.height = dp(WINDOW_HEIGHT_DP)
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager?.updateViewLayout(overlayView, params)
            }
        }
    }

    /** 双击包子：最小化（只留包子，窗口缩到 84dp）/ 展开（恢复橙色内容区）。包子位置不变，动画照常 */
    private fun toggleMinimize() {
        if (isIdleMode || isTakeOverMode || isConfirmMode) return
        isMinimized = !isMinimized
        overlayView?.post {
            panelView?.visibility = if (isMinimized) View.GONE else View.VISIBLE
            textView?.visibility = if (isMinimized) View.GONE else View.VISIBLE
            actionRowTag?.visibility = View.GONE
            ensureWorkingSize()
        }
    }

    /** 切换到人机协作模式 */
    private fun setTakeOverMode(message: String) {
        println("[OverlayService] setTakeOverMode: $message")
        isIdleMode = false
        isMinimized = false   // 需要用户操作，自动展开
        overlayView?.post {
            overlayView?.visibility = View.VISIBLE
            ensureWorkingSize()
            panelView?.visibility = View.VISIBLE
            idleIconView?.visibility = View.VISIBLE
            textView?.visibility = View.VISIBLE
            textView?.text = "🖐 $message"
            actionRowTag?.visibility = View.VISIBLE
            actionButton?.visibility = View.VISIBLE
            actionButton?.text = "继续"
            actionButton?.setTextColor(Color.parseColor("#3B1A00"))
            cancelButton?.visibility = View.GONE
            syncBunAnimation()
        }
    }

    /** 切换到正常（工作）模式：包子 + 右侧橙色面板；普通模式不提供停止按钮 */
    private fun setNormalMode() {
        println("[OverlayService] setNormalMode")
        isIdleMode = false
        overlayView?.post {
            panelView?.visibility = if (isMinimized) View.GONE else View.VISIBLE
            idleIconView?.visibility = View.VISIBLE
            askUserPanel?.visibility = View.GONE
            textView?.visibility = if (isMinimized) View.GONE else View.VISIBLE
            actionRowTag?.visibility = View.GONE
            actionButton?.visibility = View.GONE
            actionButton?.text = ""
            cancelButton?.visibility = View.GONE
            ensureWorkingSize()
            syncBunAnimation()
        }
    }

    /** 把窗口恢复成工作态尺寸 360×84（包子位置不变，只是右侧展开面板） */
    private fun ensureWorkingSize() {
        val params = overlayParams ?: return
        params.width = dp(if (isMinimized) IDLE_WINDOW_WIDTH_DP else WORK_WINDOW_WIDTH_DP)
        params.height = dp(WINDOW_HEIGHT_DP)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager?.updateViewLayout(overlayView, params)
    }

    /** 暂停/继续：冻结或恢复包子动画；状态真正变化时用 Toast 提示 */
    private fun applyPauseState(paused: Boolean, notify: Boolean = false) {
        overlayView?.post {
            syncBunAnimation()
            if (notify) {
                Toast.makeText(
                    applicationContext,
                    if (paused) "任务已暂停" else "任务继续",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 包子压缩动画：一个循环 = 横向压缩到 95% 再还原，然后纵向压缩到 95% 再还原。
     * 只在「工作中且未暂停、未等待用户操作」时播放，其余状态冻结在当前帧。
     */
    private fun syncBunAnimation() {
        val shouldRun = !isIdleMode && !isPaused && !isTakeOverMode && !isConfirmMode
        if (shouldRun) playBunAnimation() else freezeBunAnimation()
    }

    private fun playBunAnimation() {
        val icon = idleIconView ?: return
        val anim = bunAnimator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BUN_ANIM_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = null   // 线性时间轴，缓动由 sin 提供
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val squash = 1f - BUN_SQUASH_SCALE
                if (t < 0.5f) {
                    // 前半段：横向压缩再还原
                    val k = kotlin.math.sin(Math.PI * (t / 0.5f)).toFloat()
                    icon.scaleX = 1f - squash * k
                    icon.scaleY = 1f
                } else {
                    // 后半段：纵向压缩再还原
                    val k = kotlin.math.sin(Math.PI * ((t - 0.5f) / 0.5f)).toFloat()
                    icon.scaleX = 1f
                    icon.scaleY = 1f - squash * k
                }
            }
        }.also { bunAnimator = it }

        when {
            !anim.isStarted -> anim.start()
            anim.isPaused -> anim.resume()
        }
    }

    private fun freezeBunAnimation() {
        val anim = bunAnimator ?: return
        if (anim.isRunning) anim.pause()
    }

    private fun resetBunAnimation() {
        bunAnimator?.cancel()
        idleIconView?.scaleX = 1f
        idleIconView?.scaleY = 1f
    }

    /** 切换到敏感操作确认模式 */
    private fun setConfirmMode(message: String) {
        println("[OverlayService] setConfirmMode: $message")
        isIdleMode = false
        isMinimized = false   // 需要用户操作，自动展开
        overlayView?.post {
            // 确保悬浮窗可见
            overlayView?.visibility = View.VISIBLE
            ensureWorkingSize()
            panelView?.visibility = View.VISIBLE
            idleIconView?.visibility = View.VISIBLE
            textView?.visibility = View.VISIBLE
            textView?.text = "⚠️ $message"
            actionRowTag?.visibility = View.VISIBLE
            actionButton?.visibility = View.VISIBLE
            actionButton?.text = "确认"
            actionButton?.setTextColor(Color.parseColor("#3B1A00"))
            // 显示取消按钮
            cancelButton?.setTextColor(Color.parseColor("#7A1F1F"))
            cancelButton?.visibility = View.VISIBLE
            syncBunAnimation()
        }
    }
}


