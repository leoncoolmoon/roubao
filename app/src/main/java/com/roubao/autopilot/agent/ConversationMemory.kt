package com.roubao.autopilot.agent

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 对话记忆管理 - 保存完整对话历史，图片用完即删
 *
 * 参考 Open-AutoGLM 的设计：
 * - 保持完整对话历史，让模型看到之前所有操作
 * - 图片用完即删，节省 token
 */
class ConversationMemory {

    private val messages = mutableListOf<Message>()

    /**
     * 本轮待发送的截图。**不进历史**，只在渲染请求时作为最后一条消息追加。
     *
     * 之前把图挂在当轮的 user 消息上，下一轮再删掉，导致同一条历史消息在两次请求里
     * 序列化结果不同 —— 前缀从那张图的位置开始就全部失配，缓存命中率永远是 0。
     */
    private var pendingImageBase64: String? = null

    /**
     * 消息类型
     */
    data class Message(
        val role: String,  // "system", "user", "assistant"
        val textContent: String,
        var imageBase64: String? = null  // 已废弃：历史消息不再带图
    )

    /**
     * 添加系统消息（通常只在开始时添加一次）
     */
    fun addSystemMessage(text: String) {
        messages.add(Message(role = "system", textContent = text))
    }

    /**
     * 添加用户消息（截图单独走 pendingImage，不写进历史）
     */
    fun addUserMessage(text: String, image: Bitmap? = null) {
        messages.add(Message(role = "user", textContent = text))
        pendingImageBase64 = image?.let { bitmapToBase64(it) }
    }

    /**
     * 添加助手消息
     */
    fun addAssistantMessage(text: String) {
        messages.add(Message(role = "assistant", textContent = text))
    }

    /**
     * 清掉本轮待发送的截图（响应回来后调用）。
     * 历史消息本身不含图，所以这里不会改变任何已发送过的内容。
     */
    fun stripLastUserImage() {
        pendingImageBase64 = null
    }

    /**
     * 获取最近 N 条消息（不包括系统消息）
     */
    fun getRecentMessages(count: Int): List<Message> {
        val nonSystemMessages = messages.filter { it.role != "system" }
        return nonSystemMessages.takeLast(count)
    }

    /**
     * 构建 OpenAI 兼容的 messages JSON
     * @param includeImages 是否附加本轮截图
     */
    fun toMessagesJson(includeImages: Boolean = true): JSONArray {
        val jsonArray = JSONArray()

        // 历史消息永远只序列化成 "role + 纯文本"，逐字节稳定，才能命中前缀缓存
        for (msg in messages) {
            jsonArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.textContent)
            })
        }

        val image = pendingImageBase64
        if (includeImages && !image.isNullOrBlank()) {
            val contentArray = JSONArray()
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", "Current screen (latest screenshot):")
            })
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$image")
                })
            })
            jsonArray.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        return jsonArray
    }

    /**
     * 获取消息数量
     */
    fun size(): Int = messages.size

    /**
     * 清空所有消息
     */
    fun clear() {
        messages.clear()
    }

    /**
     * 获取 token 估算（粗略）
     * 中文约 2 字符/token，英文约 4 字符/token
     */
    fun estimateTokens(): Int {
        var total = 0
        for (msg in messages) {
            // 文本 token
            total += msg.textContent.length / 3
            // 图片 token（约 1000 token 每张）
            if (msg.imageBase64 != null) {
                total += 1000
            }
        }
        return total
    }

    /**
     * Bitmap 转 Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object {
        /**
         * 创建带系统提示的记忆
         */
        fun withSystemPrompt(systemPrompt: String): ConversationMemory {
            return ConversationMemory().apply {
                addSystemMessage(systemPrompt)
            }
        }
    }
}
