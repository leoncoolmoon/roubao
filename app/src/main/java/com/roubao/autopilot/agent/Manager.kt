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
        // 反馈也放在末尾：稳定前缀才可能命中前缀缓存
        return if (feedback.isNotEmpty()) {
            "$formatted\n\n### User Feedback / Lessons (MUST FOLLOW) ###\n$feedback"
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

        if (infoPool.userReply.isNotBlank()) {
            result += "\n\n### User Reply ###\n${infoPool.userReply}"
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

        // 每步都变的内容放最后：前面那些稳定段落才可能命中前缀缓存
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

            result += "\n\n$selectionBlock"
        }

        if (infoPool.accessibilityTree.isNotBlank()) {
            result += "\n\n### Current UI Tree ###\n${infoPool.accessibilityTree.take(12000)}"
        }

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

        val plan = response
            .substringAfter("### Plan", "")
            .substringBefore("### Selected Skill", "")
            .replace("###", "")
            .trim()

        val selectedSkill = response
            .substringAfter("### Selected Skill ###", "")
            .substringBefore("###")
            .replace("`", "")
            .trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }

        return PlanResult(thought, completedSubgoal, plan, selectedSkill)
    }
}

data class PlanResult(
    val thought: String,
    val completedSubgoal: String,
    val plan: String,
    val selectedSkillId: String? = null
)
