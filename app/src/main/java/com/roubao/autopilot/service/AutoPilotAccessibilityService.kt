package com.roubao.autopilot.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        println("[AccessibilityService] 无障碍服务已断开")
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
    fun dumpNodeTree(maxNodes: Int = 80): String {
        val root = rootInActiveWindow ?: return "UI 元素树为空 (未获取到 active window)"
        val elements = mutableListOf<UIElementNode>()
        collectNodes(root, elements, maxNodes)

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
        }
    }

    /**
     * 提取当前界面中匹配查询条件的元素中心坐标（x, y）
     * 查询匹配规则：不区分大小写匹配文本、描述、ID 或类名
     */
    fun findElementCoordinates(query: String): Pair<Int, Int>? {
        if (query.isBlank()) return null
        val root = rootInActiveWindow ?: return null
        val matchedNode = findMatchingNode(root, query) ?: return null

        val rect = Rect()
        matchedNode.getBoundsInScreen(rect)
        if (rect.isEmpty) return null

        return Pair(rect.centerX(), rect.centerY())
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
    private fun collectNodes(node: AccessibilityNodeInfo?, list: MutableList<UIElementNode>, maxNodes: Int) {
        if (node == null || list.size >= maxNodes) return

        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

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
            collectNodes(node.getChild(i), list, maxNodes)
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
