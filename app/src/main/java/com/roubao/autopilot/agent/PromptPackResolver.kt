package com.roubao.autopilot.agent

import com.roubao.autopilot.data.PromptPackEntity
import com.roubao.autopilot.data.PromptRepository

/**
 * 在可用包中选择当前任务的 pack。
 *
 * - auto：选当前等级可用的最高 level；同 level 按 sort_order。
 * - 强制包：只有该包 level <= currentLevel 时才生效；否则回退 auto。
 *
 * dependence / level 只是可见性提示，不是权限锁。
 */
object PromptPackResolver {

    /** 运行时：Agent 策略包跟随当前权限等级自动选择，避免 basic Agent 策略配 adb/root 工具。 */
    fun resolveRuntime(level: AccessLevel): PromptPackEntity? = resolve(level, "auto")

    fun resolve(level: AccessLevel, mode: String): PromptPackEntity? {
        val packs = try {
            PromptRepository.getInstance().getAllPacks()
                .filter { AccessLevel.fromId(it.level).rank <= level.rank }
        } catch (e: Exception) {
            emptyList()
        }
        if (packs.isEmpty()) return null

        if (mode != "auto") {
            packs.firstOrNull { it.id == mode }?.let { return it }
        }

        return packs.maxWithOrNull(
            compareBy<PromptPackEntity> { AccessLevel.fromId(it.level).rank }
                .thenBy { it.sortOrder }
        )
    }
}
