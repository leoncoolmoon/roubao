package com.roubao.autopilot.vlm

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * VLM (Vision Language Model) API 客户端
 * 支持 OpenAI 兼容接口 (GPT-4V, Qwen-VL, Claude, etc.)
 */
class VLMClient(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-vision-preview"
) {
    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    fun modelName(): String = model

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 免费/中转模型经常 60s 以上才吐字，宁可为「不打断任务」多等
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        /** 把用量/缓存行同时送进 App 内日志（由 MobileAgent 挂上）。 */
        var logSink: ((String) -> Unit)? = null

        internal fun report(line: String) {
            println(line)
            try {
                logSink?.invoke(line)
            } catch (_: Exception) {
            }
        }

        /** 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠 */
        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }

        /**
         * 从 API 获取可用模型列表
         * @param baseUrl API 基础地址
         * @param apiKey API 密钥
         * @return 模型 ID 列表
         */
        suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
            // 验证 baseUrl 是否为空
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("Base URL 不能为空"))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // 清理 URL，确保正确拼接
            val cleanBaseUrl = normalizeUrl(baseUrl.removeSuffix("/chat/completions"))

            val request = try {
                Request.Builder()
                    .url("$cleanBaseUrl/models")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(Exception("Base URL 格式无效: ${e.message}"))
            }

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val data = json.optJSONArray("data") ?: JSONArray()
                        val models = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i)
                            if (item != null) {
                                val id = item.optString("id", "").trim()
                                if (id.isNotEmpty()) {
                                    models.add(id)
                                }
                            }
                        }
                        Result.success(models)
                    } else {
                        Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 调用 VLM 进行多模态推理 (带重试)
     */
    suspend fun predict(
        prompt: String,
        images: List<Bitmap> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 预先编码图片 (避免重试时重复编码)
        val encodeStart = System.currentTimeMillis()
        val encodedImages = images.map { bitmapToBase64Url(it) }
        val encodeMs = System.currentTimeMillis() - encodeStart
        val imageChars = encodedImages.sumOf { it.length }
        val inputChars = prompt.length + imageChars
        println(
            "[VLMClient] 准备请求: 编码 ${images.size} 张 ${encodeMs}ms, " +
                "文本 ${prompt.length} 字符, base64 ${imageChars / 1024}KB"
        )

        for (attempt in 1..MAX_RETRIES) {
            val requestStart = System.currentTimeMillis()
            try {
                val content = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                    encodedImages.forEach { imageUrl ->
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                            })
                        })
                    }
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 4096)
                    put("temperature", 0.0)
                    put("top_p", 0.85)
                    put("frequency_penalty", 0.2)  // 减少重复输出
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val (code, responseBody) = executeCancellable(request)

                if (code in 200..299) {
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val message = choice.getJSONObject("message")
                        // org.json 的 getString 会把 JSON null 变成字符串 "null"，
                        // 必须显式判 null，否则空回复会被当成正文传进解析器
                        val responseContent = if (message.isNull("content")) {
                            ""
                        } else {
                            message.optString("content", "")
                        }
                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason == "length") {
                            report("[VLMClient] ⚠️ 输出被 max_tokens 截断（finish_reason=length），动作 JSON 可能不完整")
                        }
                        println(
                            "[VLMClient] ✓ 请求完成 ${System.currentTimeMillis() - requestStart}ms " +
                                "(attempt=$attempt, 输入 $inputChars 字符, 输出 ${responseContent.length} 字符, finish=$finishReason)"
                        )
                        report("[VLMClient] 用量 ${cacheStat(json)}")
                        // 空回复是模型侧异常（例如 thinking 把正文吃光），单独重试，不当作动作格式错误
                        if (responseContent.isBlank()) {
                            val reasoning = message.optString("reasoning_content", "")
                            report(
                                "[VLMClient] ⚠️ 空回复（finish=$finishReason, reasoning=${reasoning.length} 字符），" +
                                    "重试 $attempt/$MAX_RETRIES..."
                            )
                            lastException = Exception("空回复 finish=$finishReason")
                            if (finishReason == "length") {
                                // 思考占满 max_tokens 后 content 为空：同样的问题重试也不会变，直接失败交给上层
                                report("[VLMClient] 空回复且输出被截断，重试无意义，放弃本次请求")
                                break
                            }
                            if (attempt < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS * attempt)
                            }
                            continue
                        }
                        return@withContext Result.success(responseContent)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    println("[VLMClient] ✗ HTTP $code 用时 ${System.currentTimeMillis() - requestStart}ms")
                    lastException = Exception("API error: $code - $responseBody")
                }
            } catch (e: UnknownHostException) {
                // DNS 解析失败，重试
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时，重试
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                // IO 错误，重试
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                // 其他错误，不重试
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 调用 VLM 进行多模态推理 (使用完整对话历史)
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     */
    suspend fun predictWithContext(
        messagesJson: JSONArray
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            val requestStart = System.currentTimeMillis()
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 4096)
                    put("temperature", 0.0)
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val (code, responseBody) = executeCancellable(request)

                if (code in 200..299) {
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val message = choice.getJSONObject("message")
                        // 同上：JSON null 必须显式判空，否则会变成字符串 "null"
                        val responseContent = if (message.isNull("content")) {
                            ""
                        } else {
                            message.optString("content", "")
                        }
                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason == "length") {
                            report("[VLMClient] ⚠️ 输出被 max_tokens 截断（finish_reason=length），动作 JSON 可能不完整")
                        }
                        println(
                            "[VLMClient] ✓ 上下文请求完成 ${System.currentTimeMillis() - requestStart}ms " +
                                "(attempt=$attempt, ${messagesJson.length()} 条消息, 输出 ${responseContent.length} 字符, finish=$finishReason)"
                        )
                        report("[VLMClient] 用量 ${cacheStat(json)}")
                        if (responseContent.isBlank()) {
                            val reasoning = message.optString("reasoning_content", "")
                            report(
                                "[VLMClient] ⚠️ 空回复（finish=$finishReason, reasoning=${reasoning.length} 字符），" +
                                    "重试 $attempt/$MAX_RETRIES..."
                            )
                            lastException = Exception("空回复 finish=$finishReason")
                            if (finishReason == "length") {
                                // 思考占满 max_tokens 后 content 为空：同样的问题重试也不会变，直接失败交给上层
                                report("[VLMClient] 空回复且输出被截断，重试无意义，放弃本次请求")
                                break
                            }
                            if (attempt < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS * attempt)
                            }
                            continue
                        }
                        return@withContext Result.success(responseContent)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    println("[VLMClient] ✗ 上下文请求 HTTP $code 用时 ${System.currentTimeMillis() - requestStart}ms")
                    lastException = Exception("API error: $code - $responseBody")
                }
            } catch (e: UnknownHostException) {
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 可取消的 HTTP 调用。
     *
     * 以前用 `call.execute()` 阻塞在 IO 线程里，协程取消打断不了它，
     * 用户点「停止」后要等这次请求自己超时（readTimeout 120s）才真的停下。
     * 改成 enqueue + invokeOnCancellation 后，停止会直接 cancel 当前请求。
     */
    private suspend fun executeCancellable(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation {
                try {
                    call.cancel()
                } catch (_: Exception) {
                }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: ""
                        if (cont.isActive) cont.resume(response.code to body)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        try {
                            response.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            })
        }

    /**
     * 从响应里提取用量与缓存命中。
     * DeepSeek: usage.prompt_cache_hit_tokens
     * OpenAI 兼容: usage.prompt_tokens_details.cached_tokens
     */
    private fun cacheStat(json: JSONObject): String {
        val usage = json.optJSONObject("usage") ?: return "usage 缺失（该接口不回传用量）"
        val promptTokens = usage.optInt("prompt_tokens", 0)
        val completionTokens = usage.optInt("completion_tokens", 0)
        val hitTokens = usage.optInt("prompt_cache_hit_tokens", -1).let { direct ->
            if (direct >= 0) direct
            else usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
        }
        val pct = if (promptTokens > 0) hitTokens * 100 / promptTokens else 0
        return "prompt=$promptTokens cached=$hitTokens ($pct%) completion=$completionTokens"
    }

    /**
     * Bitmap 转 Base64 URL (只压缩质量，不压缩分辨率)
     * 保持原始分辨率以确保坐标准确
     */
    private fun bitmapToBase64Url(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 JPEG 格式，质量 70%，保持原始分辨率
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[VLMClient] 图片压缩: ${bitmap.width}x${bitmap.height}, ${bytes.size / 1024}KB")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 调整图片大小
     */
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

/**
 * 常用 VLM 配置
 */
object VLMConfigs {
    // OpenAI GPT-4V
    fun gpt4v(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4-vision-preview"
    )

    // Qwen-VL (阿里云)
    fun qwenVL(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-vl-max"
    )

    // Claude (Anthropic)
    fun claude(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-3-5-sonnet-20241022"
    )

    // 自定义 (vLLM / Ollama / LocalAI)
    fun custom(apiKey: String, baseUrl: String, model: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model
    )
}
