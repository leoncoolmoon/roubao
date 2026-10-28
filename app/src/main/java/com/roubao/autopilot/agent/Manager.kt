package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * Manager Agent - 负责规划和进度管理
 */
class Manager {

    private val initialPhaseGuidance = """
        ### Planning Phase: Initial ###
        Make a high-level plan from the request and current screen. Do not mark "Finished" yet.
    """.trimIndent()

    private val updatePhaseGuidance = """
        ### Planning Phase: Update ###
        Assess progress. Mark "Finished" only when the request is fully complete, the final state is visible, the last outcome was A, and there are no consecutive failures. Otherwise update the plan or change strategy.
        Stop for payment, password, or biometric pages; do not stop for price displays or cart pages.
    """.trimIndent()

    /**
     * 生成规划 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String {
        val template = try {
            PromptRepository.getInstance().getAgentPrompt("manager", infoPool.packId)?.prompt
                ?: PromptRepository.DEFAULT_MANAGER_PROMPT
        } catch (e: Exception) {
            PromptRepository.DEFAULT_MANAGER_PROMPT
        }

        val formatted = formatManagerPrompt(infoPool, template)
        val feedback = try {
            PromptRepository.getInstance().getFeedbackPrompt(infoPool.effectiveLevel())
        } catch (e: Exception) {
            ""
        }
        // 反馈放回最前：和"UI 树在前"保持同一套顺序，避免再把大段动态内容压到生成之前
        return if (feedback.isNotEmpty()) {
            "### User Feedback / Lessons (MUST FOLLOW) ###\n$feedback\n\n$formatted"
        } else {
            formatted
        }
    }

    private fun formatManagerPrompt(infoPool: InfoPool, template: String): String {
        val completedPlanStr = if (infoPool.completedPlan.isNotEmpty() && infoPool.completedPlan != "No completed subgoal.") {
            "### Historical Operations ###\n${infoPool.completedPlan}\n\n"
        } else ""

        val planSectionStr = if (infoPool.plan.isNotEmpty()) {
            "### Current Plan ###\n${infoPool.plan}\n\n"
        } else ""

        val lastActionStr = if (infoPool.lastAction != null) {
            "### Last Action ###\n${infoPool.lastAction}\n\n"
        } else ""

        val lastSummaryStr = if (infoPool.lastSummary.isNotEmpty()) {
            "### Last Action Description ###\n${infoPool.lastSummary}\n\n"
        } else ""

        val recentOutcomesStr = if (infoPool.actionOutcomes.isNotEmpty()) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(3)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount > 0) {
                "### Recent Action Results ###\nLast ${recentOutcomes.size} actions: ${recentOutcomes.joinToString(", ")} (A=success, B=partial, C=failed)\nFailed attempts: $failCount\n\n"
            } else ""
        } else ""

        val importantNotesStr = if (infoPool.importantNotes.isNotEmpty()) {
            "### Important Notes ###\n${infoPool.importantNotes}\n\n"
        } else ""

        val errorDetailsStr = if (infoPool.errorFlagPlan) {
            buildString {
                append("### ⚠️ STUCK - Multiple Failed Attempts! ###\n")
                append("You have encountered several consecutive failed attempts:\n")
                val k = infoPool.errToManagerThresh
                val recentActions = infoPool.actionHistory.takeLast(k)
                val recentSummaries = infoPool.summaryHistory.takeLast(k)
                val recentErrors = infoPool.errorDescriptions.takeLast(k)

                recentActions.forEachIndexed { i, act ->
                    append("- Action: $act | Description: ${recentSummaries.getOrNull(i)} | Failed: ${recentErrors.getOrNull(i)}\n")
                }
                append("\nIMPORTANT: DO NOT mark as \"Finished\" when there are failed attempts! Try a different approach.\n\n")
            }
        } else ""

        val skillContextStr = if (infoPool.plan.isEmpty() && infoPool.skillContext.isNotEmpty()) {
            "### Available Skills ###\n${infoPool.skillContext}\n\n"
        } else ""

        val additionalKnowledgeStr = if (infoPool.plan.isEmpty() && infoPool.additionalKnowledge.isNotEmpty()) {
            "5. ${infoPool.additionalKnowledge}\n"
        } else ""

        val phaseGuidance = if (infoPool.plan.isEmpty()) initialPhaseGuidance else updatePhaseGuidance

        var result = template
            .replace("{{instruction}}", infoPool.instruction)
            .replace("{{phaseGuidance}}", phaseGuidance)
            .replace("{{completedPlan}}", completedPlanStr)
            .replace("{{planSection}}", planSectionStr)
            .replace("{{lastActionSection}}", lastActionStr)
            .replace("{{lastSummarySection}}", lastSummaryStr)
            .replace("{{recentOutcomes}}", recentOutcomesStr)
            .replace("{{importantNotes}}", importantNotesStr)
            .replace("{{errorDetails}}", errorDetailsStr)
            .replace("{{skillContext}}", skillContextStr)
            .replace("{{additionalKnowledge}}", additionalKnowledgeStr)

        // 注意：UI 树必须放在「规则」之前。
        // 放到最后会让模型把最后的 12k 字符节点逐一"重读"一遍，实测 reasoning 涨到 18–20k 字符，
        // 直接吃满 max_tokens 导致 content=null。缓存收益（~1.2k token）远抵不过一次失败（95–360s）。
        if (infoPool.accessibilityTree.isNotBlank()) {
            result = "### Current UI Tree ###\n${infoPool.accessibilityTree.take(12000)}\n\n$result"
        }

        if (infoPool.userReply.isNotBlank()) {
            result = "### User Reply ###\n${infoPool.userReply}\n\n$result"
        }

        if (infoPool.plan.isEmpty() && infoPool.availableSkills.isNotBlank()) {
            val selectionBlock = """

                ### Selectable Skills ###
                ${infoPool.availableSkills}

                ### Skill Selection (MANDATORY) ###
                Choose at most one skill from the list above.
                If one clearly matches the request, you MUST output its exact id.
                If none matches, output "None".
                Put this section before every other output section:
                ### Selected Skill ###
                <skill_id or None>
            """.trimIndent()

            result = "$selectionBlock\n\n$result"
        }

        result += "\n\n### Clarification Rule (AUTHORITATIVE) ###\n" +
            "Use `ask_user` when required details are missing. Use `answer` only for the final response after the task is complete. This rule overrides any other wording that says to use `answer` for questions.\n"

        val installedApps = infoPool.installedApps.ifBlank { "Unknown" }
        val installAllowed = try {
            PromptRepository.getInstance().isToolSkillEnabled("install_app")
        } catch (e: Exception) {
            false
        }
        result += "\n\n### App Availability Rule (AUTHORITATIVE) ###\n" +
            "App inventory (partial: system defaults + skill-related apps):\n$installedApps\n" +
            "This is NOT the full installed-app list. If the app you need is not listed, call `search_apps` with its name; " +
            "only after search returns no match should you treat it as not installed.\n" +
            "Do not invent a package name. A skill's related apps are hints, not requirements. " +
            "If none are installed, use a similar installed app, web version, or GUI; only ask the user when no suitable app/GUI path exists.\n" +
            "App installation via app store: ${if (installAllowed) "ALLOWED (install_app skill is ON)" else "NOT ALLOWED (install_app skill is OFF; ask the user to enable 安装应用 first)"}.\n" +
            "If the user explicitly names an installed app, call open_app with that exact name first; do not ask the user to confirm the app name unless open_app fails.\n"

        val policy = toolPolicySection(infoPool.effectiveLevel())
        if (policy.isNotBlank()) result += policy

        return result
    }

    /**
     * 解析规划响应
     */
    fun parseResponse(response: String): PlanResult {
        val thought = response
            .substringAfter("### Thought", "")
            .substringBefore("### Historical Operations")
            .substringBefore("### Plan", "")
            .replace("###", "")
            .trim()

        val completedSubgoal = if (response.contains("### Historical Operations")) {
            response
                .substringAfter("### Historical Operations", "")
                .substringBefore("### Plan", "")
                .replace("###", "")
                .trim()
        } else {
            "No completed subgoal."
        }

        // 模型有时不写 "### Plan ###" 标题，直接从编号列表开始。
        // 这时按编号行抽取，而不是把整段响应当计划（整段里可能只有一个 "null"）。
        val plan = if (response.contains("### Plan")) {
            response
                .substringAfter("### Plan", "")
                .substringBefore("### Selected Skill", "")
                .replace("###", "")
                .trim()
        } else {
            extractNumberedList(response)
        }

        val selectedSkill = response
            .substringAfter("### Selected Skill ###", "")
            .substringBefore("###")
            .replace("`", "")
            .trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.substringBefore(" ")
            ?.trim()
            ?.trimEnd('.', '。', '*', '"', '\'')
            ?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }

        return PlanResult(thought, completedSubgoal, plan, selectedSkill)
    }

    /** 抽取形如 "1. xxx" / "2) xxx" 的编号行，作为缺失标题时的计划兜底。 */
    private fun extractNumberedList(text: String): String {
        val numbered = Regex("""^\d+[.)、]\s*\S""")
        return text.lineSequence()
            .map { it.trim() }
            .filter { numbered.containsMatchIn(it) }
            .joinToString("\n")
            .trim()
    }
}

data class PlanResult(
    val thought: String,
    val completedSubgoal: String,
    val plan: String,
    val selectedSkillId: String? = null
)
