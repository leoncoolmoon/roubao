package com.roubao.autopilot.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roubao.autopilot.MainActivity
import com.roubao.autopilot.R

/**
 * 七彩悬浮窗服务 - 显示当前执行步骤
 * 放在屏幕顶部状态栏下方，不影响截图识别
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textView: TextView? = null
    private var actionButton: TextView? = null
    private var actionRowTag: LinearLayout? = null
    private var pauseButton: TextView? = null
    private var progressRing: ProgressBar? = null
    private var cancelButton: TextView? = null  // 确认模式下的取消按钮
    private var askUserDialog: AlertDialog? = null
    private var askUserPanel: LinearLayout? = null
    private var askUserInput: EditText? = null
    private var askUserQuestion: TextView? = null
    private var pendingAskAnswer: ((String) -> Unit)? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var divider: View? = null
    private var divider2: View? = null  // 确认模式下第二个分隔线
    private var animator: ValueAnimator? = null

    companion object {
        private var instance: OverlayService? = null
        private var stopCallback: (() -> Unit)? = null
        private var pauseCallback: (() -> Unit)? = null
        private var continueCallback: (() -> Unit)? = null
        private var confirmCallback: ((Boolean) -> Unit)? = null  // 敏感操作确认回调
        private var isTakeOverMode = false
        private var isConfirmMode = false  // 敏感操作确认模式
        private var isPaused = false

        // 等待 instance 回调队列
        private val pendingCallbacks = mutableListOf<() -> Unit>()

        fun show(context: Context, text: String, onStop: (() -> Unit)? = null) {
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

        fun update(text: String) {
            instance?.updateText(text)
        }

        fun setPauseCallback(callback: (() -> Unit)?) {
            pauseCallback = callback
        }

        fun updatePauseState(paused: Boolean) {
            isPaused = paused
            instance?.applyPauseState(paused)
        }

        /** 截图时临时隐藏悬浮窗 */
        fun setVisible(visible: Boolean) {
            instance?.overlayView?.post {
                instance?.overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
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
        val text = intent?.getStringExtra("text") ?: "AutoPilot"
        updateText(text)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        animator?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        // 使用用户提供的 9-patch：左侧包子，右侧橙色内容区；不再额外画图标
        val container = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.roubao_overlay_bg)
            alpha = 0.86f
        }

        val frameSize = dp(60)
        progressRing = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(frameSize, frameSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(4)
            }
        }
        pauseButton = TextView(this).apply {
            text = "Ⅱ"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(105, 18, 20, 28))
                setStroke(1, Color.argb(130, 255, 255, 255))
            }
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(20)
            }
            setOnClickListener {
                if (!isTakeOverMode && !isConfirmMode) pauseCallback?.invoke()
            }
            setOnLongClickListener {
                if (!isTakeOverMode && !isConfirmMode && !isPaused) pauseCallback?.invoke()
                openMainWindow()
                true
            }
        }
        container.addView(progressRing)
        container.addView(pauseButton)

        // 文字叠在 9-patch 右侧橙色区域
        textView = TextView(this).apply {
            text = "准备中..."
            textSize = 13f
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
                leftMargin = dp(94)
                rightMargin = dp(12)
            }
        }
        container.addView(textView)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(8) }
        }
        actionButton = TextView(this).apply {
            text = ""
            textSize = 13f
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
            textSize = 12f
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

        // ask_user：直接显示在悬浮窗内部，避免 Service 弹 Dialog 的 token 问题
        val askPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(242, 24, 26, 34))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        askUserQuestion = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        askUserInput = EditText(this).apply {
            hint = "输入补充信息"
            textSize = 13f
            setSingleLine(true)
            setImeOptions(EditorInfo.IME_ACTION_DONE)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(150, 255, 255, 255))
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
            textSize = 12f
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
        askPanel.addView(askUserQuestion)
        askPanel.addView(askUserInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ).apply { topMargin = dp(4) })
        askPanel.addView(askSubmit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(30)
        ).apply {
            topMargin = dp(6)
            gravity = Gravity.END
        })
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
            width = dp(360)
            height = dp(84)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openMainWindow() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) {
            println("[OverlayService] 打开主窗口失败: ${e.message}")
        }
    }

    private fun startRainbowAnimation(drawable: GradientDrawable) {
        val colors = intArrayOf(
            Color.parseColor("#FF6B6B"), // 红
            Color.parseColor("#FFA94D"), // 橙
            Color.parseColor("#FFE066"), // 黄
            Color.parseColor("#69DB7C"), // 绿
            Color.parseColor("#4DABF7"), // 蓝
            Color.parseColor("#9775FA"), // 紫
            Color.parseColor("#F783AC"), // 粉
            Color.parseColor("#FF6B6B")  // 回到红
        )

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val index = (fraction * (colors.size - 1)).toInt()
                val nextIndex = minOf(index + 1, colors.size - 1)
                val localFraction = (fraction * (colors.size - 1)) - index

                val color1 = interpolateColor(colors[index], colors[nextIndex], localFraction)
                val color2 = interpolateColor(
                    colors[(index + 2) % colors.size],
                    colors[(nextIndex + 2) % colors.size],
                    localFraction
                )
                val color3 = interpolateColor(
                    colors[(index + 4) % colors.size],
                    colors[(nextIndex + 4) % colors.size],
                    localFraction
                )

                drawable.colors = intArrayOf(color1, color2, color3)
                drawable.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            start()
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        return Color.argb(
            (startA + (endA - startA) * fraction).toInt(),
            (startR + (endR - startR) * fraction).toInt(),
            (startG + (endG - startG) * fraction).toInt(),
            (startB + (endB - startB) * fraction).toInt()
        )
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
        pendingAskAnswer = onAnswer
        overlayView?.post {
            askUserQuestion?.text = question
            askUserInput?.setText("")
            askUserPanel?.visibility = View.VISIBLE
            textView?.visibility = View.GONE
            pauseButton?.visibility = View.GONE
            progressRing?.visibility = View.GONE
            actionRowTag?.visibility = View.GONE

            val params = overlayParams ?: return@post
            params.height = dp(190)
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

    private fun hideAskUserPanel() {
        overlayView?.post {
            askUserPanel?.visibility = View.GONE
            textView?.visibility = View.VISIBLE
            pauseButton?.visibility = View.VISIBLE
            progressRing?.visibility = if (isPaused) View.GONE else View.VISIBLE
            actionRowTag?.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(askUserInput?.windowToken, 0)
            val params = overlayParams ?: return@post
            params.height = dp(84)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(overlayView, params)
        }
    }

    /** 切换到人机协作模式 */
    private fun setTakeOverMode(message: String) {
        println("[OverlayService] setTakeOverMode: $message")
        overlayView?.post {
            overlayView?.visibility = View.VISIBLE
            textView?.text = "🖐 $message"
            actionRowTag?.visibility = View.VISIBLE
            actionButton?.visibility = View.VISIBLE
            actionButton?.text = "继续"
            actionButton?.setTextColor(Color.parseColor("#B8F5C0"))
            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
            pauseButton?.visibility = View.GONE
            progressRing?.visibility = View.GONE
        }
    }

    /** 切换到正常模式；普通模式不提供停止按钮 */
    private fun setNormalMode() {
        println("[OverlayService] setNormalMode")
        overlayView?.post {
            actionRowTag?.visibility = View.GONE
            actionButton?.visibility = View.GONE
            actionButton?.text = ""
            pauseButton?.visibility = View.VISIBLE
            progressRing?.visibility = if (isPaused) View.GONE else View.VISIBLE
            applyPauseState(isPaused)
            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
        }
    }

    private fun applyPauseState(paused: Boolean) {
        overlayView?.post {
            pauseButton?.text = if (paused) "▶" else "Ⅱ"
            pauseButton?.setTextColor(if (paused) Color.parseColor("#B8F5C0") else Color.WHITE)
            progressRing?.visibility = if (paused) View.GONE else View.VISIBLE
        }
    }

    /** 切换到敏感操作确认模式 */
    private fun setConfirmMode(message: String) {
        println("[OverlayService] setConfirmMode: $message")
        overlayView?.post {
            // 确保悬浮窗可见
            overlayView?.visibility = View.VISIBLE
            textView?.text = "⚠️ $message"
            actionRowTag?.visibility = View.VISIBLE
            actionButton?.visibility = View.VISIBLE
            actionButton?.text = "确认"
            actionButton?.setTextColor(Color.parseColor("#B8F5C0"))
            // 显示取消按钮和第二分隔线
            divider2?.visibility = View.VISIBLE
            cancelButton?.visibility = View.VISIBLE
            pauseButton?.visibility = View.GONE
            progressRing?.visibility = View.GONE
        }
    }
}
