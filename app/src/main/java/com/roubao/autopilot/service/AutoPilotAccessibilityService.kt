package com.roubao.autopilot.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务类，用于获取屏幕 UI 节点树的信息（名称、文本、位置等）
 */
class AutoPilotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoPilotAccessibility"

        @Volatile
        private var instance: AutoPilotAccessibilityService? = null

        fun getInstance(): AutoPilotAccessibilityService? = instance

        fun isServiceEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutoPilotAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需特定处理，根据需要轮询当前 Window 的 Node
    }

    override fun onInterrupt() {
        Log.d(TAG, "AutoPilotAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "AutoPilotAccessibilityService destroyed")
    }

    /**
     * UI 节点结构
     */
    data class UiElement(
        val text: String,
        val contentDescription: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean
    ) {
        fun toPromptString(): String {
            val label = when {
                text.isNotBlank() && contentDescription.isNotBlank() -> "$text ($contentDescription)"
                text.isNotBlank() -> text
                contentDescription.isNotBlank() -> contentDescription
                else -> className.substringAfterLast('.')
            }
            return "Element['$label', bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}], clickable=$isClickable, editable=$isEditable]"
        }
    }

    /**
     * 获取当前屏幕所有可读 UI 节点列表
     */
    fun getUiElements(maxElements: Int = 50): List<UiElement> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UiElement>()
        val screenBounds = Rect()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || elements.size >= maxElements) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val className = node.className?.toString()?.trim().orEmpty()

            if ((text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) &&
                bounds.width() > 0 && bounds.height() > 0
            ) {
                elements.add(
                    UiElement(
                        text = text,
                        contentDescription = desc,
                        className = className,
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                traverse(child)
                child?.recycle()
            }
        }

        try {
            traverse(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing UI nodes", e)
        } finally {
            rootNode.recycle()
        }

        return elements
    }

    /**
     * 获取格式化的 UI 描述，直接可作为 LLM Prompt 的上下文输入
     */
    fun getFormattedUiPrompt(): String {
        val elements = getUiElements()
        if (elements.isEmpty()) return "无障碍获取 UI 信息: 未检测到可交互的 UI 元素"

        val sb = StringBuilder()
        sb.append("当前页面无障碍 UI 元素列表 (共 ${elements.size} 个):\n")
        elements.forEachIndexed { index, elem ->
            sb.append("${index + 1}. ").append(elem.toPromptString()).append("\n")
        }
        return sb.toString()
    }
}
