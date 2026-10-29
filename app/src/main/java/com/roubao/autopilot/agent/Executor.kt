package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * Executor Agent - 决定具体执行什么动作
 */
class Executor {

    private fun resolveCurrentSubgoal(infoPool: InfoPool): String {
        if (infoPool.currentSubgoal.isNotBlank()) return infoPool.currentSubgoal
        val plan = infoPool.plan
        if (plan.isBlank()) return "No active subgoal."
        val numberedRegex = Regex("""^\d+[.)]\s+""")
        val numbered = plan.lineSequence()
            .map { it.trim() }
            .firstOrNull { numberedRegex.containsMatchIn(it) }
            ?.replaceFirst(numberedRegex, "")
            ?.trim()
        if (!numbered.isNullOrBlank()) return numbered
        return plan.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "No active subgoal."
    }

    /**
     * 生成执行 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String {
        val rawPrompt = try {
            PromptRepository.getInstance().getAgentPrompt("executor", infoPool.packId)?.prompt
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
                    when (outcome) {
                        "A" -> append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Successful\n")
                        // U = 反思模型没跑成功，不代表动作失败
                        "U" -> append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Unknown (reflection unavailable)\n")
                        else -> append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Failed | Error: ${latestErrors.getOrNull(i)}\n")
                    }
                }
            }
        } else {
            "No actions have been taken yet.\n"
        }

        val currentSubgoal = resolveCurrentSubgoal(infoPool)
        val installedApps = infoPool.installedApps.ifBlank { "Unknown" }
        val installAllowed = try {
            PromptRepository.getInstance().isToolSkillEnabled("install_app")
        } catch (e: Exception) {
            false
        }

        var prompt = rawPrompt
            .replace("{{instruction}}", infoPool.instruction)
            .replace("{{plan}}", infoPool.plan)
            .replace("{{currentSubgoal}}", currentSubgoal)
            .replace("{{installedApps}}", installedApps)
            .replace("{{progressStatus}}", infoPool.progressStatus.ifEmpty { "No progress yet." })
            .replace("{{actionHistory}}", actionHistoryStr)

        val feedback = try {
            PromptRepository.getInstance().getFeedbackPrompt(infoPool.effectiveLevel())
        } catch (e: Exception) {
            ""
        }
        if (feedback.isNotEmpty()) {
            prompt = "### User Feedback / Lessons (MUST FOLLOW) ###\n$feedback\n\n$prompt"
        }

        if (infoPool.activeSkillContext.isNotBlank()) {
            prompt += "\n\n### Active Skill ###\n${infoPool.activeSkillContext}\n"
        }

        prompt += "\n\n### App Availability Rule (AUTHORITATIVE) ###\n" +
            "App inventory (partial: system defaults + skill-related apps):\n$installedApps\n" +
            "This is NOT the full installed-app list. If the app you need is not listed, call `search_apps` with its name " +
            "before concluding it is not installed. Never call open_app with a package you invented.\n" +
            "If the current skill's related apps are not installed, they are not hard requirements: " +
            "use a similar installed app, web version, or GUI; only use `ask_user` when no suitable app/GUI path exists.\n" +
            "App installation via app store: ${if (installAllowed) "ALLOWED (install_app skill is ON)" else "NOT ALLOWED (install_app skill is OFF; ask the user to enable 安装应用 first)"}.\n" +
            "If the user explicitly names an installed app, call open_app with that exact name first; only ask if open_app fails.\n"

        if (infoPool.lastToolResult.isNotBlank()) {
            prompt += "\n\n### Last Tool Result ###\n${infoPool.lastToolResult}\n"
        }

        if (infoPool.userReply.isNotBlank()) {
            prompt += "\n\n### User Reply ###\n${infoPool.userReply}\n"
        }

        prompt += "\n\n### Visual Input ###\n" +
            "Screenshots are currently ${if (infoPool.visionEnabled) "ON" else "OFF"}. " +
            "If the UI tree is sufficient and you do not need pixels, disable screenshots with " +
            "{\"action\":\"set_screenshot\",\"enabled\":false}. " +
            "Re-enable with true when visual confirmation is required.\n" +
            "If required details are missing, use " +
            "{\"action\":\"ask_user\",\"message\":\"your question\"} instead of guessing. " +
            "Use `answer` only for the final response after the task is complete. " +
            "This overrides any other rule that says to use `answer` for questions.\n"

        if (infoPool.accessibilityTree.isNotEmpty()) {
            prompt += "\n\n### Accessibility UI Tree (AUTHORITATIVE) ###\n"
            val modelWidth = if (infoPool.imageWidth > 0) infoPool.imageWidth else infoPool.screenWidth
            val modelHeight = if (infoPool.imageHeight > 0) infoPool.imageHeight else infoPool.screenHeight
            prompt += "Screen: ${modelWidth}x${modelHeight}. " +
                "Coordinates here are ABSOLUTE PIXELS of this screen/image. " +
                "Ignore any older 1080x2400 example; use the Screen size above.\n"
            prompt += "For click/double_tap/long_press: `text` identifies the control; `coordinate` disambiguates duplicate text. " +
                "If the same text appears more than once, you MUST use the Duplicate Text Candidates section and put the " +
                "chosen occurrence's center into `coordinate`. Never assume the first duplicate. " +
                "This overrides any earlier 'Text > coordinates' rule for duplicate text.\n"
            prompt += "Optional index mode: each node is numbered 1..N. You may use " +
                "{\"action\":\"click\",\"index\":N}, {\"action\":\"long_press\",\"index\":N}, " +
                "{\"action\":\"type\",\"index\":N,\"text\":\"...\"} or " +
                "{\"action\":\"scroll\",\"index\":N,\"direction\":\"down\"}. " +
                "Index is a snapshot; if the page animates, scrolls, or an ad is moving, prefer text + coordinate.\n"
            prompt += "${infoPool.accessibilityTree}\n"
        } else {
            println("[Executor] Accessibility UI Tree is EMPTY; falling back to screenshot coordinates")
        }
        val policy = toolPolicySection(infoPool.effectiveLevel())
        if (policy.isNotBlank()) prompt += policy

        println("[Executor] prompt=${prompt.length} chars, uiTree=${infoPool.accessibilityTree.length} chars")

        return prompt
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
