package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * Executor Agent - 决定具体执行什么动作
 */
class Executor {

    companion object {
        val GUIDELINES = """
General:
- For any pop-up window, close it (e.g., by clicking 'Don't Allow' or 'Accept') before proceeding.
- For requests that are questions, remember to use the `answer` action to reply before finish!
- If the desired state is already achieved, you can just complete the task.

Action Related:
- Use `open_app` to open an app, do not use the app drawer.
- Consider using `swipe` to reveal additional content.
- If swiping doesn't change the page, it may have reached the bottom.

Text Related:
- To input text: first click the input box, make sure keyboard is visible, then use `type` action.
- To clear text: long press the backspace button in the keyboard.
        """.trimIndent()
    }

    /**
     * 生成执行 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String {
        val rawPrompt = try {
            PromptRepository.getInstance().getAgentPrompt("executor")?.prompt
                ?: PromptRepository.DEFAULT_EXECUTOR_PROMPT
        } catch (e: Exception) {
            PromptRepository.DEFAULT_EXECUTOR_PROMPT
        }

        val actionHistoryStr = if (infoPool.actionHistory.isNotEmpty()) {
            val numActions = minOf(5, infoPool.actionHistory.size)
            val latestActions = infoPool.actionHistory.takeLast(numActions)
            val latestSummaries = infoPool.summaryHistory.takeLast(numActions)
            val latestOutcomes = infoPool.actionOutcomes.takeLast(numActions)
            val latestErrors = infoPool.errorDescriptions.takeLast(numActions)

            buildString {
                latestActions.forEachIndexed { i, act ->
                    val outcome = latestOutcomes.getOrNull(i) ?: "?"
                    if (outcome == "A") {
                        append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Successful\n")
                    } else {
                        append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Failed | Error: ${latestErrors.getOrNull(i)}\n")
                    }
                }
            }
        } else {
            "No actions have been taken yet.\n"
        }

        return rawPrompt
            .replace("{{instruction}}", infoPool.instruction)
            .replace("{{plan}}", infoPool.plan)
            .replace("{{progressStatus}}", infoPool.progressStatus.ifEmpty { "No progress yet." })
            .replace("{{actionHistory}}", actionHistoryStr)
    }

    /**
     * 解析执行响应
     * 支持两种格式:
     * 1. 标准格式 (### Thought / ### Action / ### Description)
     * 2. MAI-UI 格式 (<thinking>...</thinking><tool_call>...</tool_call>)
     */
    fun parseResponse(response: String): ExecutorResult {
        // 检测是否为 MAI-UI 格式
        if (response.contains("<tool_call>") || response.contains("<thinking>")) {
            return parseMAIUIResponse(response)
        }

        // 标准格式解析
        val thought = response
            .substringAfter("### Thought", "")
            .substringBefore("### Action")
            .replace("###", "")
            .trim()

        val actionStr = response
            .substringAfter("### Action", "")
            .substringBefore("### Description")
            .replace("###", "")
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val description = response
            .substringAfter("### Description", "")
            .replace("###", "")
            .trim()

        val action = Action.fromJson(actionStr)

        return ExecutorResult(thought, action, actionStr, description)
    }

    /**
     * 解析 MAI-UI 格式响应
     * 格式: <thinking>...</thinking><tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
     */
    private fun parseMAIUIResponse(response: String): ExecutorResult {
        val thought = Action.extractThinking(response)
        val action = Action.fromMAIUIFormat(response)

        // 从 action 生成描述
        val description = when (action?.type) {
            "click" -> "点击坐标 (${action.x}, ${action.y})"
            "long_press" -> "长按坐标 (${action.x}, ${action.y})"
            "double_tap" -> "双击坐标 (${action.x}, ${action.y})"
            "swipe" -> {
                if (action.direction != null) {
                    "向${action.direction}滑动"
                } else {
                    "从 (${action.x}, ${action.y}) 滑动到 (${action.x2}, ${action.y2})"
                }
            }
            "type" -> "输入文字: ${action.text}"
            "open_app" -> "打开应用: ${action.text}"
            "system_button" -> "按${action.button}键"
            "wait" -> "等待"
            "terminate" -> "任务${if (action.status == "success") "完成" else "失败"}"
            "answer" -> "回答: ${action.text?.take(30)}"
            "take_over" -> "请求用户: ${action.text}"
            else -> action?.type ?: "未知动作"
        }

        val actionStr = action?.toJson() ?: ""
        return ExecutorResult(thought, action, actionStr, description)
    }
}

data class ExecutorResult(
    val thought: String,
    val action: Action?,
    val actionStr: String,
    val description: String
)
