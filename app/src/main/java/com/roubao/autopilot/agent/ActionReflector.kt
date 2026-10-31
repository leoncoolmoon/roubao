package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * ActionReflector Agent - 反思动作是否成功
 */
class ActionReflector {

    /**
     * 生成反思 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String {
        val rawPrompt = try {
            PromptRepository.getInstance().getAgentPrompt("reflector")?.prompt
                ?: PromptRepository.DEFAULT_REFLECTOR_PROMPT
        } catch (e: Exception) {
            PromptRepository.DEFAULT_REFLECTOR_PROMPT
        }

        val summaryWithTarget = if (infoPool.lastClickTarget.isNotBlank()) {
            "${infoPool.lastSummary}\nClicked target: ${infoPool.lastClickTarget}"
        } else {
            infoPool.lastSummary
        }

        return rawPrompt
            .replace("{{instruction}}", infoPool.instruction)
            .replace("{{completedPlan}}", infoPool.completedPlan.ifEmpty { "No progress yet." })
            .replace("{{lastAction}}", infoPool.lastAction?.toString() ?: "")
            .replace("{{lastSummary}}", summaryWithTarget)
    }

    /**
     * 解析反思响应
     */
    fun parseResponse(response: String): ReflectorResult {
        val outcomeSection = response
            .substringAfter("### Outcome", "")
            .substringBefore("### Error Description")
            .replace("###", "")
            .trim()

        val outcome = when {
            outcomeSection.contains("A") -> "A"
            outcomeSection.contains("B") -> "B"
            outcomeSection.contains("C") -> "C"
            else -> "C"
        }

        val errorDescription = response
            .substringAfter("### Error Description", "")
            .replace("###", "")
            .trim()

        return ReflectorResult(outcome, errorDescription)
    }
}

data class ReflectorResult(
    val outcome: String,  // A, B, C
    val errorDescription: String
)
