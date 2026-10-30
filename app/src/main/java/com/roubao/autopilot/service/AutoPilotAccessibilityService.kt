package com.roubao.autopilot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务 - 提取 UI 元素树、坐标精确定位及截图失败盲操
 */
class AutoPilotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: AutoPilotAccessibilityService? = null

        fun getInstance(): AutoPilotAccessibilityService? = instance

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        println("[AccessibilityService] 无障碍服务已连接")
        try {
            com.roubao.autopilot.agent.AccessLevelManager.refresh(this)
        } catch (e: Exception) {
            println("[AccessibilityService] refresh access level failed: ${e.message}")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        println("[AccessibilityService] 无障碍服务已断开")
        try {
            com.roubao.autopilot.agent.AccessLevelManager.refresh(this)
        } catch (_: Exception) {
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可用于监听窗口变化等事件
    }

    override fun onInterrupt() {
        println("[AccessibilityService] 无障碍服务中断")
    }

    // ==================== 无障碍手势 ====================

    /** 单击坐标；basic 模式不依赖 shell input tap。 */
    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchPath(path, 60L)
    }

    /** 长按坐标。 */
    fun longPressAt(x: Int, y: Int, durationMs: Long = 600L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchPath(path, durationMs)
    }

    /** 双击坐标。 */
    fun doubleTapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val first = GestureDescription.StrokeDescription(path, 0L, 50L)
        val second = GestureDescription.StrokeDescription(path, 120L, 50L)
        return try {
            dispatchGesture(GestureDescription.Builder().addStroke(first).addStroke(second).build(), null, null)
        } catch (e: Exception) {
            false
        }
    }

    /** 滑动；basic 模式不依赖 shell input swipe。 */
    fun swipeAt(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 500L): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchPath(path, durationMs)
    }

    /** 无障碍全局动作：BACK / HOME / RECENTS。 */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * API 30+ 的无障碍截图：全屏（含其他 App），不需要 MediaProjection 授权，
     * 但有最小调用间隔。失败返回 null，由 DeviceController 回退到 shell/tree-only。
     */
    fun takeScreenshotBitmap(timeoutMs: Long = 2500L): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        return try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val buffer = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                        } catch (e: Exception) {
                            println("[AccessibilityService] takeScreenshot decode failed: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        println("[AccessibilityService] takeScreenshot failed: $errorCode")
                        latch.countDown()
                    }
                }
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            bitmap
        } catch (e: Exception) {
            println("[AccessibilityService] takeScreenshot exception: ${e.message}")
            null
        }
    }

    /**
     * UI 节点数据结构
     */
    data class UIElementNode(
        val text: String,
        val contentDescription: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val centerX: Int,
        val centerY: Int,
        val isClickable: Boolean,
        val isEditable: Boolean
    )

    /**
     * Dump 当前窗口所有可用的 UI 节点树结构信息
     */
    fun dumpNodeTree(maxNodes: Int = 80, scale: Float = 1f): String {
        val root = rootInActiveWindow ?: return "UI 元素树为空 (未获取到 active window)"
        // 收集比展示上限更多的节点，用于检测重复文本候选；展示仍只取前 maxNodes。
        val allElements = mutableListOf<UIElementNode>()
        collectNodes(root, allElements, maxOf(maxNodes, 300), scale)
        val elements = allElements.take(maxNodes)

        if (elements.isEmpty()) {
            return "界面未提取到可见 UI 节点元素"
        }

        return buildString {
            append("当前 UI 元素树 (共 ${elements.size} 个节点):\n")
            elements.forEachIndexed { index, node ->
                append("${index + 1}. ")
                if (node.text.isNotEmpty()) append("文本:「${node.text}」 ")
                if (node.contentDescription.isNotEmpty()) append("描述:「${node.contentDescription}」 ")
                if (node.viewId.isNotEmpty()) append("ID:${node.viewId} ")
                append("类型:${node.className.substringAfterLast('.')} ")
                append("坐标中心:(${node.centerX}, ${node.centerY}) ")
                append("区域:[${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}] ")
                if (node.isClickable) append("[可点击] ")
                if (node.isEditable) append("[可输入] ")
                append("\n")
            }
            append(duplicateTextCandidates(allElements))
        }
    }

    /**
     * 同名/同描述候选集。模型在多个「确定」之间必须靠坐标选择，
     * 不能依赖代码取第一个节点。
     */
    private fun duplicateTextCandidates(
        elements: List<UIElementNode>,
        maxGroups: Int = 6,
        maxPerGroup: Int = 4
    ): String {
        val groups = LinkedHashMap<String, MutableList<UIElementNode>>()
        elements.forEach { node ->
            val label = node.text.ifBlank { node.contentDescription }.trim()
            if (label.isBlank()) return@forEach
            val key = normalizeUiText(label)
            if (key.isBlank()) return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(node)
        }

        val duplicates = groups.values.filter { it.size > 1 }.take(maxGroups)
        if (duplicates.isEmpty()) return ""

        return buildString {
            append("\n### Duplicate Text Candidates (AUTHORITATIVE) ###\n")
            duplicates.forEach { list ->
                val label = list.first().text.ifBlank { list.first().contentDescription }
                append("「").append(label).append("」出现 ").append(list.size).append(" 次：\n")
                list.take(maxPerGroup).forEach { node ->
                    append("- center=(").append(node.centerX).append(", ").append(node.centerY).append(")")
                    append(" bounds=[").append(node.bounds.left).append(",").append(node.bounds.top)
                    append(",").append(node.bounds.right).append(",").append(node.bounds.bottom).append("]")
                    if (node.isClickable) append(" [clickable]")
                    append('\n')
                }
                if (list.size > maxPerGroup) {
                    append("- ... 还有 ").append(list.size - maxPerGroup).append(" 个\n")
                }
            }
            append("Duplicated text: choose the intended occurrence by coordinate, ")
            append("and put that occurrence's center in `coordinate`.\n")
        }
    }

    private fun normalizeUiText(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), "")

    /**
     * 返回所有匹配的候选节点（exact 优先，其次 contains），而不是第一个。
     */
    fun findElementCandidates(query: String, limit: Int = 12): List<UIElementNode> {
        if (query.isBlank()) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val all = mutableListOf<UIElementNode>()
        collectNodes(root, all, 400)
        val q = normalizeUiText(query)
        if (q.isEmpty()) return emptyList()

        val exact = all.filter { node ->
            normalizeUiText(node.text) == q ||
                normalizeUiText(node.contentDescription) == q ||
                normalizeUiText(node.viewId) == q
        }
        val pool = if (exact.isNotEmpty()) {
            exact
        } else {
            all.filter { node ->
                (node.text.isNotEmpty() && normalizeUiText(node.text).contains(q)) ||
                    (node.contentDescription.isNotEmpty() && normalizeUiText(node.contentDescription).contains(q)) ||
                    (node.viewId.isNotEmpty() && normalizeUiText(node.viewId).contains(q))
            }
        }
        return pool
            .distinctBy {
                "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}|${it.text}|${it.contentDescription}"
            }
            .take(limit)
    }

    /**
     * 直接把文本写入当前焦点（或第一个）可编辑节点，绕过输入法/键盘状态。
     * 适用于“英文输入但当前是中文输入法”的场景，避免去点键盘“中”键。
     */
    fun setTextOnFocusedEditable(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
        val target = if (focused?.isEditable == true) focused else findFirstEditable(root)
        if (target == null) return false

        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            println("[AccessibilityService] setText failed: ${e.message}")
            false
        }
    }

    /**
     * 在焦点输入框里设置选区（start == end 就是移动光标）。
     * 比长按拖拽/方向键更精确，适合“选中几个字/移动光标”。
     */
    fun setSelection(start: Int, end: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
        val editable = if (focused?.isEditable == true) focused else findFirstEditable(root)
        val target = editable ?: return false

        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start.coerceAtLeast(0))
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end.coerceAtLeast(0))
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (e: Exception) {
            println("[AccessibilityService] setSelection failed: ${e.message}")
            false
        }
    }

    /**
     * 对当前可滚动节点执行翻页（比 swipe 更稳）。
     */
    fun scrollPage(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null

        fun find(node: AccessibilityNodeInfo?) {
            if (node == null || target != null || !node.isVisibleToUser) return
            if (node.isScrollable) {
                target = node
                return
            }
            for (i in 0 until node.childCount) {
                find(node.getChild(i))
            }
        }

        find(root)
        val node = target ?: return false
        val action = when (direction.lowercase()) {
            "up", "backward", "back" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "left", "right" -> return false
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return try {
            node.performAction(action)
        } catch (e: Exception) {
            println("[AccessibilityService] scrollPage failed: ${e.message}")
            false
        }
    }

    /**
     * 按 UI 树编号（从 1 开始）点击节点。
     * 编号与 dumpNodeTree() 中的序号一致，每次动作前重新取树，避免使用过期节点。
     */
    fun clickByIndex(index: Int): Boolean {
        var node = nodeByIndex(index) ?: return false
        while (node != null && !node.isClickable) {
            node = node.parent
        }
        return try {
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } catch (e: Exception) {
            println("[AccessibilityService] clickByIndex failed: ${e.message}")
            false
        }
    }

    fun longPressByIndex(index: Int): Boolean {
        var node = nodeByIndex(index) ?: return false
        while (node != null && !node.isLongClickable) {
            node = node.parent
        }
        return try {
            node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        } catch (e: Exception) {
            println("[AccessibilityService] longPressByIndex failed: ${e.message}")
            false
        }
    }

    fun setTextByIndex(index: Int, text: String): Boolean {
        val node = nodeByIndex(index) ?: return false
        val target = if (node.isEditable) node else findEditableChild(node) ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            println("[AccessibilityService] setTextByIndex failed: ${e.message}")
            false
        }
    }

    fun scrollByIndex(index: Int, forward: Boolean): Boolean {
        var node = nodeByIndex(index) ?: return false
        while (node != null && !node.isScrollable) {
            node = node.parent
        }
        return try {
            node?.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ) == true
        } catch (e: Exception) {
            println("[AccessibilityService] scrollByIndex failed: ${e.message}")
            false
        }
    }

    private fun nodeByIndex(index: Int): AccessibilityNodeInfo? {
        if (index <= 0) return null
        val root = rootInActiveWindow ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodeRefs(root, nodes, 80)
        return nodes.getOrNull(index - 1)
    }

    private fun collectNodeRefs(
        node: AccessibilityNodeInfo?,
        list: MutableList<AccessibilityNodeInfo>,
        maxNodes: Int
    ) {
        if (node == null || list.size >= maxNodes) return
        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.toString() ?: ""
            if (!rect.isEmpty &&
                (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty() ||
                    node.isClickable || node.isEditable)
            ) {
                list.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            if (list.size >= maxNodes) break
            collectNodeRefs(node.getChild(i), list, maxNodes)
        }
    }

    private fun findEditableChild(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditableChild(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /**
     * 根据元素属性进行盲操（在截图失败/黑屏或无障碍直接操作场景下使用）
     * @param query 匹配的关键词（文本/描述/viewId）
     * @param textToType 如果是输入类型动作，需要输入的文本
     * @return 操作是否成功触发
     */
    fun performActionByAttribute(query: String, textToType: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = findMatchingNode(root, query) ?: return false

        if (!textToType.isNullOrEmpty() && targetNode.isEditable) {
            // 设置文本盲操
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (success) return true
        }

        // 尝试触发点击盲操（若节点不可点击，向父节点查找可点击节点）
        var clickableNode: AccessibilityNodeInfo? = targetNode
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }

        if (clickableNode != null && clickableNode.isClickable) {
            return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        return false
    }

    /**
     * 递归收集可用 UI 节点
     */
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        list: MutableList<UIElementNode>,
        maxNodes: Int,
        scale: Float = 1f
    ) {
        if (node == null || list.size >= maxNodes) return

        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // 截图被缩小后，坐标也要按同一比例缩放，保证「UI 树坐标 == 截图坐标」
            if (scale != 1f) {
                rect.set(
                    (rect.left * scale).toInt(),
                    (rect.top * scale).toInt(),
                    (rect.right * scale).toInt(),
                    (rect.bottom * scale).toInt()
                )
            }

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.toString() ?: ""

            // 过滤无意义空白且无 ID 的占位节点
            if (!rect.isEmpty && (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty() || node.isClickable || node.isEditable)) {
                list.add(
                    UIElementNode(
                        text = text,
                        contentDescription = desc,
                        viewId = id,
                        className = node.className?.toString() ?: "",
                        bounds = rect,
                        centerX = rect.centerX(),
                        centerY = rect.centerY(),
                        isClickable = node.isClickable,
                        isEditable = node.isEditable
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            if (list.size >= maxNodes) break
            collectNodes(node.getChild(i), list, maxNodes, scale)
        }
    }

    /**
     * 递归查找匹配包含 query 的 UI 节点
     */
    private fun findMatchingNode(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val lowerQuery = query.lowercase().trim()
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.toString()?.lowercase() ?: ""

        if ((text.isNotEmpty() && text.contains(lowerQuery)) ||
            (desc.isNotEmpty() && desc.contains(lowerQuery)) ||
            (id.isNotEmpty() && id.contains(lowerQuery))
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val result = findMatchingNode(node.getChild(i), query)
            if (result != null) return result
        }

        return null
    }
}
