package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptRepository

/**
 * Notetaker Agent - 记录重要信息
 */
class Notetaker {

    /**
     * 生成笔记 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String {
        val rawPrompt = try {
            PromptRepository.getInstance().getAgentPrompt("notetaker")?.prompt
                ?: PromptRepository.DEFAULT_NOTETAKER_PROMPT
        } catch (e: Exception) {
            PromptRepository.DEFAULT_NOTETAKER_PROMPT
        }

        return rawPrompt
            .replace("{{instruction}}", infoPool.instruction)
            .replace("{{progressStatus}}", infoPool.progressStatus.ifEmpty { "No progress yet." })
            .replace("{{importantNotes}}", infoPool.importantNotes.ifEmpty { "No important notes recorded." })
    }

    /**
     * 解析笔记响应
     */
    fun parseResponse(response: String): String {
        return response
            .substringAfter("### Important Notes", "")
            .replace("###", "")
            .trim()
    }
}
