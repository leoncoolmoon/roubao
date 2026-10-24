package com.roubao.autopilot.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.roubao.autopilot.App
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.data.PromptRepository
import com.roubao.autopilot.skills.SkillManager
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.GUIOwlClient
import com.roubao.autopilot.vlm.MAIUIClient
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 *
 * 新增 Skill 层支持：
 * - 快速路径：高置信度 delegation Skill 直接执行
 * - 增强模式：GUI 自动化 Skill 提供上下文指导
 *
 * 支持三种模式：
 * - OpenAI 兼容模式：使用 VLMClient (Manager → Executor → Reflector)
 * - GUI-Owl 模式：使用 GUIOwlClient (直接返回操作指令)
 * - MAI-UI 模式：使用 MAIUIClient (专用 prompt 和对话历史)
 */
class MobileAgent(
    private var vlmClient: VLMClient?,
    private val controller: DeviceController,
    private val context: Context,
    private val guiOwlClient: GUIOwlClient? = null,  // GUI-Owl 专用客户端
    private val maiuiClient: MAIUIClient? = null,    // MAI-UI 专用客户端
    private val accessibilityModeEnabled: Boolean = false  // 无障碍模式开关
) {
    // 是否使用 GUI-Owl 模式
    private val useGUIOwlMode: Boolean = guiOwlClient != null
    // 是否使用 MAI-UI 模式
    private val useMAIUIMode: Boolean = maiuiClient != null
    // App 扫描器 (使用 App 单例中的实例)
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            println("[肉包] SkillManager 已加载，共 ${it.getAllSkills().size} 个 Skills")
            // 设置 VLM 客户端用于意图匹配（仅在 OpenAI 兼容模式下）
            vlmClient?.let { client -> it.setVLMClient(client) }
        }
    } catch (e: Exception) {
        println("[肉包] SkillManager 加载失败: ${e.message}")
        null
    }

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /** 暂停时切换模型：保留 ConversationMemory，仅替换 HTTP 客户端。 */
    fun updateVlmClient(client: VLMClient) {
        vlmClient = client
        skillManager?.setVLMClient(client)
        log("已切换模型客户端: ${client.modelName()}")
    }

    /**
     * 执行指令
     */
    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false
    ): AgentResult {
        log("开始执行: $instruction")

        // 立即显示悬浮窗，不等意图分析/应用扫描完成
        OverlayService.show(context, "准备执行...") {
            updateState { copy(isRunning = false) }
            stop()
        }
        OverlayService.setPauseCallback {
            togglePause()
            OverlayService.updatePauseState(_state.value.isPaused)
        }

        // 根据模式选择执行路径
        if (useGUIOwlMode && guiOwlClient != null) {
            log("使用 GUI-Owl 模式")
            return runInstructionWithGUIOwl(instruction, maxSteps)
        }

        if (useMAIUIMode && maiuiClient != null) {
            log("使用 MAI-UI 模式")
            return runInstructionWithMAIUI(instruction, maxSteps)
        }

        // OpenAI 兼容模式需要 VLMClient
        if (vlmClient == null) {
            log("错误: VLMClient 未初始化")
            return AgentResult(success = false, message = "VLMClient 未初始化")
        }

        log("使用 OpenAI 兼容模式")

        // 使用 LLM 匹配 Skill，生成上下文信息给 Agent（不执行任何操作）
        log("正在分析意图...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        val infoPool = InfoPool(instruction = instruction)
        infoPool.availableSkills = try {
            skillManager?.getEnabledSkillRoster()
                ?.joinToString("\n") { "- ${it.id}: ${it.description}" }
                .orEmpty()
        } catch (e: Exception) {
            ""
        }
        log("可用 Skill 数: ${infoPool.availableSkills.lineSequence().count { it.trimStart().startsWith("- ") }}")

        // 初始化 Executor 的对话记忆
        val executorSystemPrompt = buildString {
            append("You are an agent who can operate an Android phone. ")
            append("Decide the next action based on the current state.\n\n")
            append("User Request: $instruction\n")
        }
        infoPool.executorMemory = ConversationMemory.withSystemPrompt(executorSystemPrompt)
        log("已初始化对话记忆")

        // 如果有 Skill 上下文，添加到 InfoPool，让 Manager 知道可用的工具
        if (!skillContext.isNullOrEmpty() && skillContext != "未找到相关技能或可用应用，请使用通用 GUI 自动化完成任务。") {
            infoPool.skillContext = skillContext
            log("已匹配到可用技能:\n$skillContext")
        } else {
            log("未匹配到特定技能，使用通用 GUI 自动化")
        }

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height
        log("屏幕尺寸: ${width}x${height}")

        // 获取已安装应用列表（只取非系统应用，限制数量避免 prompt 过长）
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .take(50)
            .map { it.appName }
        infoPool.installedApps = apps.joinToString(", ")
        log("已加载 ${apps.size} 个应用")

        updateState { copy(isRunning = true, isPaused = false, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                // 检查协程是否被取消
                coroutineContext.ensureActive()
                awaitIfPaused()

                // 检查是否被用户停止
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 提取无障碍 UI 树；截图必须实时获取，不能复用旧帧
                val accessibilityService = com.roubao.autopilot.service.AutoPilotAccessibilityService.getInstance()
                if (accessibilityModeEnabled && accessibilityService != null) {
                    infoPool.accessibilityTree = accessibilityService.dumpNodeTree()
                    log("已成功提取无障碍 UI 元素树")
                } else {
                    infoPool.accessibilityTree = ""
                }

                val treeOnly = !infoPool.visionEnabled && infoPool.accessibilityTree.isNotBlank()
                var screenshot: Bitmap? = null
                if (treeOnly) {
                    log("视觉输入已关闭，使用实时 UI 树")
                } else {
                    log("截图中...")
                    OverlayService.setVisible(false)
                    delay(100) // 等待悬浮窗隐藏
                    val screenshotResult = controller.screenshotWithFallback()
                    OverlayService.setVisible(true)
                    screenshot = screenshotResult.bitmap

                    // 处理敏感页面（截图被系统阻止）
                    if (screenshotResult.isSensitive) {
                        log("⚠️ 检测到敏感页面（截图被阻止），请求人工接管")
                        val confirmed = withContext(Dispatchers.Main) {
                            waitForUserConfirm("检测到敏感页面，是否继续执行？")
                        }
                        if (!confirmed) {
                            log("用户取消，任务终止")
                            OverlayService.idle(context)
                            bringAppToFront()
                            return AgentResult(success = false, message = "敏感页面，用户取消")
                        }
                        log("用户确认继续（使用黑屏占位图/无障碍元素树辅助定位）")
                    } else if (screenshotResult.isFallback) {
                        log("⚠️ 截图失败，使用黑屏占位图及无障碍元素树继续盲操")
                    }
                }

                val modelClient = vlmClient
                    ?: return AgentResult(success = false, message = "VLMClient 未初始化")

                // 再次检查停止状态（截图后）
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 2. 检查错误升级
                checkErrorEscalation(infoPool)

                // 3. 跳过 Manager 的情况
                val skipManager = !infoPool.errorFlagPlan &&
                        infoPool.actionHistory.isNotEmpty() &&
                        infoPool.actionHistory.last().type == "invalid"

                // 4. Manager 规划
                if (!skipManager && isAgentEnabled("manager")) {
                    log("Manager 规划中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.idle(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val planPrompt = manager.getPrompt(infoPool)
                    val planResponse = modelClient.predict(planPrompt, screenshot?.let { listOf(it) } ?: emptyList())

                    // VLM 调用后检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.idle(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    if (planResponse.isFailure) {
                        log("Manager 调用失败: ${planResponse.exceptionOrNull()?.message}")
                        continue
                    }

                    val rawPlan = planResponse.getOrThrow()
                    val planResult = manager.parseResponse(rawPlan)
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan

                    planResult.selectedSkillId?.let { selectedId ->
                        infoPool.selectedSkillId = selectedId
                        val selectedPrompt = try {
                            skillManager?.getSkillPrompt(selectedId)
                        } catch (e: Exception) {
                            null
                        }
                        if (!selectedPrompt.isNullOrBlank()) {
                            infoPool.activeSkillContext = selectedPrompt
                            log("Manager 选择 Skill: $selectedId")
                        } else {
                            log("Manager 选择了未知/不可用 Skill: $selectedId")
                        }
                    }

                    log("计划: ${planResult.plan.take(100)}...")

                    // 检查是否遇到敏感页面
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        log("检测到敏感页面（支付/密码等），已停止执行")
                        OverlayService.update("敏感页面，已停止")
                        delay(2000)
                        OverlayService.idle(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        return AgentResult(success = false, message = "检测到敏感页面（支付/密码），已安全停止")
                    }

                    // 检查是否完成
                    if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                        log("任务完成!")
                        OverlayService.update("完成!")
                        delay(1500)
                        OverlayService.idle(context)
                        updateState { copy(isRunning = false, isCompleted = true) }
                        bringAppToFront()
                        return AgentResult(success = true, message = "任务完成")
                    }
                }

                // 5. Executor 决定动作 (使用上下文记忆)
                log("Executor 决策中...")

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                val actionPrompt = executor.getPrompt(infoPool)

                // 使用上下文记忆调用 VLM
                val memory = infoPool.executorMemory
                val actionResponse = if (memory != null) {
                    // 添加用户消息（带截图）
                    memory.addUserMessage(actionPrompt, screenshot)
                    log("记忆消息数: ${memory.size()}, 估算 token: ${memory.estimateTokens()}")

                    // 调用 VLM
                    val response = modelClient.predictWithContext(memory.toMessagesJson())

                    // 删除图片节省 token
                    memory.stripLastUserImage()

                    response
                } else {
                    // 降级：使用普通方式
                    modelClient.predict(actionPrompt, screenshot?.let { listOf(it) } ?: emptyList())
                }

                // VLM 调用后检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                if (actionResponse.isFailure) {
                    log("Executor 调用失败: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                val executorResult = executor.parseResponse(responseText)

                // 将助手响应添加到记忆
                memory?.addAssistantMessage(responseText)
                val action = executorResult.action

                log("思考: ${executorResult.thought.take(80)}...")
                log("动作: ${executorResult.actionStr}")
                log("描述: ${executorResult.description}")

                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                if (action == null) {
                    log("动作解析失败")
                    infoPool.actionHistory.add(Action(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // 特殊处理: 需要用户补充信息
                if (action.type == "ask_user") {
                    val question = action.message ?: action.text ?: "请补充完成任务所需的信息"
                    log("需要用户补充: $question")
                    OverlayService.update("等待用户输入: ${question.take(30)}")
                    val reply = withTimeoutOrNull(5 * 60 * 1000L) {
                        waitForUserInput(question)
                    }
                    if (reply == null) {
                        OverlayService.dismissAskUser()
                        infoPool.userReply = "用户未在 5 分钟内回复，请根据现有信息继续。"
                        log("用户未回复，按超时策略继续")
                    } else {
                        infoPool.userReply = reply.ifBlank { "用户跳过了补充。" }
                        log("用户补充: ${infoPool.userReply.take(200)}")
                    }
                    infoPool.actionHistory.add(action)
                    infoPool.summaryHistory.add("等待用户补充")
                    infoPool.actionOutcomes.add("A")
                    continue
                }

                // 特殊处理: answer 动作
                if (action.type == "answer") {
                    // 模型可能把最终回答放在 message 而不是 text；两者都接受，避免 null
                    val answerText = action.text?.takeIf { it.isNotBlank() }
                        ?: action.message?.takeIf { it.isNotBlank() }
                        ?: "任务完成"
                    log("回答: $answerText")
                    OverlayService.update("${answerText.take(20)}...")
                    delay(1500)
                    OverlayService.idle(context)
                    updateState { copy(isRunning = false, isCompleted = true, answer = answerText) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: $answerText")
                }

                // 特殊处理: terminate 动作 (MAI-UI)
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log("任务${if (success) "完成" else "失败"}")
                    OverlayService.update(if (success) "完成!" else "失败")
                    delay(1500)
                    OverlayService.idle(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 6. 敏感操作确认
                if (action.needConfirm || action.message != null && action.type in listOf("click", "double_tap", "long_press")) {
                    val confirmMessage = action.message ?: "确认执行此操作？"
                    log("⚠️ 敏感操作: $confirmMessage")

                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm(confirmMessage)
                    }

                    if (!confirmed) {
                        log("❌ 用户取消操作")
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add("用户取消: ${executorResult.description}")
                        infoPool.actionOutcomes.add("C")
                        infoPool.errorDescriptions.add("User cancelled")
                        continue
                    }
                    log("✅ 用户确认，继续执行")
                }

                // 7. 执行动作
                log("执行动作: ${action.type}")
                OverlayService.update("${action.type}: ${executorResult.description.take(15)}...")
                val toolResult = executeAction(action, infoPool)
                if (!toolResult.isNullOrBlank()) {
                    infoPool.lastToolResult = toolResult.take(4000)
                    log("工具结果已回填: ${toolResult.take(120)}...")
                }
                infoPool.lastAction = action

                // 立即记录执行步骤（outcome 暂时为 "?" 表示进行中）
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = executorResult.description,
                    thought = executorResult.thought,
                    outcome = "?" // 进行中
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 等待动作生效
                delay(if (step == 0) 5000 else 2000)

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 8. 截图 (动作后，隐藏悬浮窗)
                OverlayService.setVisible(false)
                delay(100)
                val afterScreenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val afterScreenshot = afterScreenshotResult.bitmap
                if (afterScreenshotResult.isFallback) {
                    log("动作后截图失败，使用黑屏占位图")
                }

                // 9. Reflector 反思
                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                val reflectResult = if (!isAgentEnabled("reflector")) {
                    log("Reflector 已停用，跳过反思（按 A 处理）")
                    ReflectorResult("A", "")
                } else {
                    log("Reflector 反思中...")
                    val reflectPrompt = reflector.getPrompt(infoPool)
                    val reflectResponse = modelClient.predict(reflectPrompt, listOfNotNull(screenshot, afterScreenshot))
                    if (reflectResponse.isSuccess) {
                        reflector.parseResponse(reflectResponse.getOrThrow())
                    } else {
                        ReflectorResult("C", "Failed to call reflector")
                    }
                }

                log("结果: ${reflectResult.outcome} - ${reflectResult.errorDescription.take(50)}")

                // 更新历史
                infoPool.actionHistory.add(action)
                infoPool.summaryHistory.add(executorResult.description)
                infoPool.actionOutcomes.add(reflectResult.outcome)
                infoPool.errorDescriptions.add(reflectResult.errorDescription)
                infoPool.progressStatus = infoPool.completedPlan

                // 更新执行步骤的 outcome（之前添加的步骤 outcome 是 "?"）
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = reflectResult.outcome
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 10. Notetaker (可选)
                if (useNotetaker && isAgentEnabled("notetaker") && reflectResult.outcome == "A" && action.type != "answer") {
                    log("Notetaker 记录中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.idle(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val notePrompt = notetaker.getPrompt(infoPool)
                    val noteResponse = modelClient.predict(notePrompt, listOf(afterScreenshot))
                    if (noteResponse.isSuccess) {
                        infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                    }
                }
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.idle(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.idle(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * GUI-Owl 模式执行指令
     * 简化流程：截图 → GUI-Owl API → 解析操作 → 执行
     */
    private suspend fun runInstructionWithGUIOwl(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = guiOwlClient ?: return AgentResult(false, "GUIOwlClient 未初始化")

        // 重置会话
        client.resetSession()
        log("GUI-Owl 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 显示悬浮窗
        OverlayService.show(context, "GUI-Owl 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, isPaused = false, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()
                awaitIfPaused()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (GUI-Owl) ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 GUI-Owl API
                log("调用 GUI-Owl API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("GUI-Owl 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thought.take(100)}...")
                log("操作: ${result.operation}")
                log("说明: ${result.explanation}")

                // 3. 解析操作指令
                val parsedAction = client.parseOperation(result.operation)
                if (parsedAction == null) {
                    log("无法解析操作: ${result.operation}")
                    continue
                }

                // 记录执行步骤
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = parsedAction.type,
                    description = result.explanation,
                    thought = result.thought,
                    outcome = "?"
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (parsedAction.type == "finish") {
                    log("任务完成!")
                    OverlayService.update("完成!")
                    delay(1500)
                    OverlayService.idle(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "任务完成")
                }

                // 4. 执行动作
                log("执行动作: ${parsedAction.type}")
                OverlayService.update("${parsedAction.type}: ${result.explanation.take(15)}...")
                executeGUIOwlAction(parsedAction, screenWidth, screenHeight)

                // 更新步骤状态
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (step < updatedSteps.size) {
                        updatedSteps[step] = updatedSteps[step].copy(outcome = "A")
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 等待动作生效
                delay(if (step == 0) 3000 else 1500)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.idle(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.idle(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 GUI-Owl 解析的动作
     */
    private suspend fun executeGUIOwlAction(
        action: GUIOwlClient.ParsedAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        when (action.type) {
            "click" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("点击: ($x, $y)")
                controller.tap(x, y)
            }
            "swipe" -> {
                val x1 = action.x ?: return@withContext
                val y1 = action.y ?: return@withContext
                val x2 = action.x2 ?: return@withContext
                val y2 = action.y2 ?: return@withContext
                log("滑动: ($x1, $y1) -> ($x2, $y2)")
                controller.swipe(x1, y1, x2, y2)
            }
            "long_press" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                controller.type(text)
            }
            "scroll" -> {
                val direction = action.text ?: "down"
                val centerX = screenWidth / 2
                val centerY = screenHeight / 2
                log("滚动: $direction")
                when (direction) {
                    "up" -> controller.swipe(centerX, centerY + 300, centerX, centerY - 300)
                    "down" -> controller.swipe(centerX, centerY - 300, centerX, centerY + 300)
                    "left" -> controller.swipe(centerX + 300, centerY, centerX - 300, centerY)
                    "right" -> controller.swipe(centerX - 300, centerY, centerX + 300, centerY)
                }
            }
            "system_button" -> {
                when (action.text?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                }
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * MAI-UI 模式执行指令
     * 使用专用的 MAI-UI prompt 和对话历史管理
     */
    private suspend fun runInstructionWithMAIUI(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = maiuiClient ?: return AgentResult(false, "MAIUIClient 未初始化")

        // 重置会话
        client.reset()
        log("MAI-UI 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 设置可用应用列表
        val installedApps = appScanner.getApps().map { it.appName }
        client.setAvailableApps(installedApps)
        log("已加载 ${installedApps.size} 个应用")

        // 显示悬浮窗
        OverlayService.show(context, "MAI-UI 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, isPaused = false, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()
                awaitIfPaused()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (MAI-UI) ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.idle(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 MAI-UI API
                log("调用 MAI-UI API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("MAI-UI 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thinking.take(150)}...")

                val action = result.action
                if (action == null) {
                    log("无法解析动作")
                    continue
                }

                log("动作: ${action.type}")

                // 记录执行步骤
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = result.thinking.take(50),
                    thought = result.thinking,
                    outcome = "?"
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log(if (success) "任务完成!" else "任务失败")
                    OverlayService.update(if (success) "完成!" else "失败")
                    delay(1500)
                    OverlayService.idle(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 检查是否需要人工接管
                if (action.type == "ask_user") {
                    log("请求用户介入: ${action.text}")
                    OverlayService.update("请手动操作: ${action.text?.take(20)}")
                    // 等待用户操作后继续
                    delay(5000)
                    continue
                }

                // 检查是否是回答
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("答案: ${action.text?.take(30)}")
                    delay(3000)
                    OverlayService.idle(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 3. 执行动作
                log("执行动作: ${action.type}")
                OverlayService.update("${action.type}...")
                executeMAIUIAction(action, screenWidth, screenHeight)

                // 更新步骤状态
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (step < updatedSteps.size) {
                        updatedSteps[step] = updatedSteps[step].copy(outcome = "A")
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 等待动作生效
                delay(if (step == 0) 2000 else 1000)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.idle(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.idle(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 MAI-UI 解析的动作
     */
    private suspend fun executeMAIUIAction(
        action: com.roubao.autopilot.vlm.MAIUIAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        // 转换归一化坐标到屏幕像素
        val screenAction = action.toScreenCoordinates(screenWidth, screenHeight)

        when (action.type) {
            "click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("点击: ($x, $y)")
                controller.tap(x, y)
            }
            "long_press" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "double_click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("双击: ($x, $y)")
                controller.tap(x, y)
                delay(100)
                controller.tap(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                controller.type(text)
            }
            "swipe" -> {
                // 支持方向式滑动和坐标式滑动
                if (action.direction != null) {
                    val centerX = screenWidth / 2
                    val centerY = screenHeight / 2
                    val distance = screenHeight / 3
                    log("滑动: ${action.direction}")
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY)
                    }
                } else if (screenAction.x != null && screenAction.y != null) {
                    // 从指定位置滑动
                    val startX = screenAction.x.toInt()
                    val startY = screenAction.y.toInt()
                    val distance = screenHeight / 3
                    val direction = action.direction ?: "up"
                    when (direction.lowercase()) {
                        "up" -> controller.swipe(startX, startY, startX, startY - distance)
                        "down" -> controller.swipe(startX, startY, startX, startY + distance)
                        "left" -> controller.swipe(startX, startY, startX - distance, startY)
                        "right" -> controller.swipe(startX, startY, startX + distance, startY)
                    }
                }
            }
            "drag" -> {
                val x1 = screenAction.startX?.toInt() ?: return@withContext
                val y1 = screenAction.startY?.toInt() ?: return@withContext
                val x2 = screenAction.endX?.toInt() ?: return@withContext
                val y2 = screenAction.endY?.toInt() ?: return@withContext
                log("拖拽: ($x1, $y1) -> ($x2, $y2)")
                controller.swipe(x1, y1, x2, y2, 500)
            }
            "open" -> {
                val appName = action.text ?: return@withContext
                log("打开应用: $appName")
                controller.openApp(appName)
            }
            "system_button" -> {
                when (action.button?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                    "menu" -> {
                        log("按菜单键")
                        // 通过 keyevent 模拟菜单键
                        controller.back()  // 大多数场景用返回代替
                    }
                    "enter" -> {
                        log("按回车键")
                        controller.enter()
                    }
                }
            }
            "wait" -> {
                log("等待...")
                delay(2000)
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 执行具体动作 (在 IO 线程执行，避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool): String? = withContext(Dispatchers.IO) {
        // 动态获取屏幕尺寸（处理横竖屏切换）
        val (screenWidth, screenHeight) = controller.getScreenSize()
        val accessibilityService = com.roubao.autopilot.service.AutoPilotAccessibilityService.getInstance()

        when (action.type) {
            "click", "double_tap", "long_press" -> {
                // 可选索引模式：模型显式给出 index 时优先按 UI 树编号操作
                if (action.index != null && accessibilityModeEnabled && accessibilityService != null) {
                    val ok = when (action.type) {
                        "click" -> accessibilityService.clickByIndex(action.index)
                        "long_press" -> accessibilityService.longPressByIndex(action.index)
                        else -> false
                    }
                    if (ok) {
                        log("🤖 无障碍索引${action.type}: index=${action.index}")
                        return@withContext null
                    }
                    log("索引操作失败，回退文本/坐标: index=${action.index}")
                }

                var x = mapCoordinate(action.x ?: 0, screenWidth)
                var y = mapCoordinate(action.y ?: 0, screenHeight)
                val modelX = x
                val modelY = y
                var calibrated = false

                // 只使用模型显式给出的元素文本做无障碍匹配；
                // 不要用 lastSummary（整句描述），否则几乎永远匹配不到节点。
                val query = action.text?.takeIf { it.isNotBlank() }

                if (accessibilityModeEnabled && accessibilityService != null) {
                    if (query != null) {
                        val exactCoords = accessibilityService.findElementCoordinates(query)
                        if (exactCoords != null) {
                            x = exactCoords.first
                            y = exactCoords.second
                            calibrated = true
                            log("🎯 无障碍文本校准${action.type}: ($x, $y) [查询: $query]")
                        } else if (action.type == "click" && accessibilityService.performActionByAttribute(query)) {
                            log("🤖 无障碍直接触发节点点击: [查询: $query]")
                            return@withContext null
                        }
                    }

                    // 不再做“几何吸附”：输入法键盘是独立窗口，rootInActiveWindow
                    // 往往只拿到下层 App，吸附会把坐标跳到聊天/设置界面上。
                    // 没有 text 时直接使用模型给的绝对坐标。
                }

                log("${action.type}: ($x, $y) [模型原始: ($modelX, $modelY), 已校准: $calibrated]")
                when (action.type) {
                    "click" -> controller.tap(x, y)
                    "double_tap" -> controller.doubleTap(x, y)
                    "long_press" -> controller.longPress(x, y)
                }
            }
            "swipe" -> {
                // 支持两种 swipe 方式:
                // 1. 坐标方式: coordinate + coordinate2
                // 2. 方向方式: direction (up/down/left/right) + optional coordinate
                if (action.direction != null) {
                    // 方向方式 (MAI-UI 格式)
                    val centerX = action.x?.let { mapCoordinate(it, screenWidth) } ?: (screenWidth / 2)
                    val centerY = action.y?.let { mapCoordinate(it, screenHeight) } ?: (screenHeight / 2)
                    val distance = 400  // 滑动距离
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY)
                        else -> log("未知滑动方向: ${action.direction}")
                    }
                } else {
                    // 坐标方式
                    val x1 = mapCoordinate(action.x ?: 0, screenWidth)
                    val y1 = mapCoordinate(action.y ?: 0, screenHeight)
                    val x2 = mapCoordinate(action.x2 ?: 0, screenWidth)
                    val y2 = mapCoordinate(action.y2 ?: 0, screenHeight)
                    controller.swipe(x1, y1, x2, y2)
                }
            }
            "type" -> {
                val text = action.text
                if (text != null) {
                    if (action.index != null && accessibilityModeEnabled && accessibilityService != null) {
                        if (accessibilityService.setTextByIndex(action.index, text)) {
                            log("🤖 无障碍索引输入成功: index=${action.index}")
                            return@withContext null
                        }
                        log("索引输入失败，回退焦点输入: index=${action.index}")
                    }
                    // 优先用无障碍 ACTION_SET_TEXT 直接写入，绕过输入法中英文状态，
                    // 避免为了切英文去点键盘“中”键而误触。
                    val setDirectly = if (accessibilityModeEnabled && accessibilityService != null) {
                        accessibilityService.setTextOnFocusedEditable(text)
                    } else false
                    if (setDirectly) {
                        log("type: 已通过无障碍直接写入 ${text.length} 个字符（无需切换键盘）")
                    } else {
                        controller.type(text)
                    }
                }
            }
            "set_screenshot" -> {
                val enable = action.enabled ?: true
                if (!enable && infoPool.accessibilityTree.isBlank()) {
                    log("无法关闭视觉输入：当前 UI 树为空")
                    infoPool.visionEnabled = true
                } else {
                    infoPool.visionEnabled = enable
                    log(if (enable) "视觉输入已开启" else "视觉输入已关闭（使用 UI 树）")
                }
            }
            "shell" -> {
                if (!PromptRepository.getInstance().isToolSkillEnabled("shell")) {
                    log("shell 工具已禁用，拒绝执行")
                    return@withContext null
                }
                val command = action.command
                if (!command.isNullOrBlank()) {
                    log("shell: $command")
                    val output = controller.shell(command)
                    log("shell 输出: ${output.take(800)}")
                    return@withContext output
                }
            }
            "key" -> {
                val key = action.key
                if (!key.isNullOrBlank()) {
                    log("按键: $key")
                    controller.keyEvent(key)
                }
            }
            "set_selection" -> {
                val start = action.start ?: 0
                val end = action.end ?: start
                val ok = if (accessibilityModeEnabled && accessibilityService != null) {
                    accessibilityService.setSelection(start, end)
                } else false
                log("选择文本: [$start, $end] -> ${if (ok) "成功" else "失败"}")
            }
            "scroll" -> {
                val direction = action.direction ?: action.text ?: "down"
                if (action.index != null && accessibilityModeEnabled && accessibilityService != null) {
                    val forward = direction.lowercase() != "up" && direction.lowercase() != "back"
                    if (accessibilityService.scrollByIndex(action.index, forward)) {
                        log("🤖 无障碍索引滚动: index=${action.index}, direction=$direction")
                        return@withContext null
                    }
                    log("索引滚动失败，回退页面滚动: index=${action.index}")
                }
                val ok = if (accessibilityModeEnabled && accessibilityService != null) {
                    accessibilityService.scrollPage(direction)
                } else false
                if (ok) {
                    log("滚动: $direction (无障碍)")
                } else {
                    val centerX = screenWidth / 2
                    val centerY = screenHeight / 2
                    log("滚动: $direction (坐标 fallback)")
                    when (direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + 400, centerX, centerY - 400)
                        "down" -> controller.swipe(centerX, centerY - 400, centerX, centerY + 400)
                        "left" -> controller.swipe(centerX + 400, centerY, centerX - 400, centerY)
                        "right" -> controller.swipe(centerX - 400, centerY, centerX + 400, centerY)
                    }
                }
            }
            "system_button" -> {
                when (action.button) {
                    "Back", "back" -> controller.back()
                    "Home", "home" -> controller.home()
                    "Enter", "enter" -> controller.enter()
                    else -> log("未知系统按钮: ${action.button}")
                }
            }
            "open_app" -> {
                if (!PromptRepository.getInstance().isToolSkillEnabled("open_app")) {
                    log("open_app 工具已禁用，拒绝执行")
                    return@withContext null
                }
                action.text?.let { appName ->
                    // 智能匹配包名 (客户端模糊搜索，省 token)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("找到应用: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("未找到应用: $appName，尝试直接打开")
                        controller.openApp(appName)
                    }
                }
            }
            "wait" -> {
                // 智能等待：模型决定等待时长
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("等待 ${duration} 秒...")
                delay(duration * 1000L)
            }
            "take_over" -> {
                // 人机协作：暂停等待用户手动完成操作
                val message = action.message ?: "请完成操作后点击继续"
                log("🖐 人机协作: $message")
                withContext(Dispatchers.Main) {
                    waitForUserTakeOver(message)
                }
                log("✅ 用户已完成，继续执行")
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
        null
    }

    /**
     * 等待用户完成手动操作（人机协作）
     */
    private suspend fun waitForUserInput(question: String): String {
        val answer = kotlinx.coroutines.CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            com.roubao.autopilot.ui.OverlayService.showAskUser(question) { text ->
                if (!answer.isCompleted) answer.complete(text)
            }
        }
        return answer.await()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showTakeOver(message) {
            if (continuation.isActive) {
                continuation.resume(Unit) {}
            }
        }
    }

    /**
     * 等待用户确认敏感操作
     * @return true = 用户确认，false = 用户取消
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) {
                continuation.resume(confirmed) {}
            }
        }
    }

    /**
     * 坐标处理：模型输出的是截图的绝对像素坐标（与 input tap 的显示坐标一致）。
     *
     * 不要再用 value < 1000 来判断“归一化坐标”：在 1080x2400 屏幕上，
     * 屏幕上方 42% 的绝对 y 都小于 1000，会被错误放大 2.4 倍。
     * 如果以后要支持 0-999 归一化的模型，请按模型/配置显式区分，而不是按数值猜。
     *
     * @param value 模型输出的坐标值
     * @param screenMax 屏幕实际尺寸
     */
    private fun mapCoordinate(value: Int, screenMax: Int): Int {
        return value.coerceIn(0, (screenMax - 1).coerceAtLeast(0))
    }

    /**
     * 检查错误升级
     */
    private fun checkErrorEscalation(infoPool: InfoPool) {
        infoPool.errorFlagPlan = false
        val thresh = infoPool.errToManagerThresh

        if (infoPool.actionOutcomes.size >= thresh) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount == thresh) {
                infoPool.errorFlagPlan = true
            }
        }
    }

    // 停止回调（由 MainActivity 设置，用于取消协程）
    var onStopRequested: (() -> Unit)? = null

    /**
     * 停止执行
     */
    fun stop() {
        OverlayService.idle(context)
        updateState { copy(isRunning = false, isPaused = false) }
        // 通知 MainActivity 取消协程
        onStopRequested?.invoke()
    }

    /**
     * 暂停执行（在当前步骤的检查点生效）
     */
    fun pause() {
        if (_state.value.isRunning && !_state.value.isPaused) {
            updateState { copy(isPaused = true) }
            OverlayService.updatePauseState(true)
            log("⏸ 已暂停，点击继续恢复")
        }
    }

    /**
     * 恢复执行
     */
    fun resume() {
        if (_state.value.isPaused) {
            updateState { copy(isPaused = false) }
            OverlayService.updatePauseState(false)
            log("▶ 继续执行")
        }
    }

    fun togglePause() {
        if (_state.value.isPaused) resume() else pause()
    }

    private fun isAgentEnabled(id: String): Boolean = try {
        com.roubao.autopilot.data.PromptRepository.getInstance().isAgentEnabled(id)
    } catch (e: Exception) {
        true
    }

    private suspend fun awaitIfPaused() {
        if (!_state.value.isPaused) return
        while (_state.value.isPaused && _state.value.isRunning) {
            delay(200)
        }
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * 返回肉包App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("返回App失败: ${e.message}")
        }
    }

    private fun log(message: String) {
        println("[肉包] $message")
        _logs.value = _logs.value + message
    }

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }

}

data class AgentState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList()
)

data class AgentResult(
    val success: Boolean,
    val message: String
)
