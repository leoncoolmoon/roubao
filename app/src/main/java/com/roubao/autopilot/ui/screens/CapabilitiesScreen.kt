package com.roubao.autopilot.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.agent.AccessLevel
import com.roubao.autopilot.agent.AccessLevelManager
import com.roubao.autopilot.data.AgentPromptEntity
import com.roubao.autopilot.data.PromptRepository
import com.roubao.autopilot.data.ToolSkillPromptEntity
import com.roubao.autopilot.data.UserFeedbackEntity
import com.roubao.autopilot.ui.theme.BaoziTheme
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 结构定义：当前正在编辑 Prompt 的项
 */
sealed class PromptTarget {
    data class AgentTarget(val agent: AgentPromptEntity) : PromptTarget()
    data class ToolSkillTarget(val item: ToolSkillPromptEntity) : PromptTarget()
}

/**
 * 能力展示与管理页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen() {
    val colors = BaoziTheme.colors

    val repository = remember { PromptRepository.getInstance() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentLevel by AccessLevelManager.level.collectAsState()

    var refreshTrigger by remember { mutableStateOf(0) }

    var agentsList by remember { mutableStateOf<List<AgentPromptEntity>>(emptyList()) }
    var toolSkillList by remember { mutableStateOf<List<ToolSkillPromptEntity>>(emptyList()) }
    var feedbackList by remember { mutableStateOf<List<UserFeedbackEntity>>(emptyList()) }

    var singleExportTarget by remember { mutableStateOf<PromptTarget?>(null) }
    var singleImportTarget by remember { mutableStateOf<PromptTarget?>(null) }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val result = repository.importPromptsJson(json)
                    withContext(Dispatchers.Main) {
                        refreshTrigger++
                        val summary = "导入: ${result.agents} Agent, ${result.tools} Tool/Skill, ${result.feedback} 反馈"
                        showToast(
                            if (result.errors.isEmpty()) summary
                            else summary + "\n" + result.errors.take(3).joinToString("\n")
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { showToast("导入失败: ${e.message}") }
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = repository.exportPromptsJson()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) { showToast("Prompt JSON 已导出") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { showToast("导出失败: ${e.message}") }
                }
            }
        }
    }

    val singleExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = singleExportTarget
        singleExportTarget = null
        if (uri != null && target != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = when (target) {
                        is PromptTarget.AgentTarget -> repository.exportAgentJson(target.agent)
                        is PromptTarget.ToolSkillTarget -> repository.exportToolSkillJson(target.item)
                    }
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) { showToast("单条 Prompt 已导出") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { showToast("导出失败: ${e.message}") }
                }
            }
        }
    }

    val singleImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = singleImportTarget
        singleImportTarget = null
        if (uri != null && target != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val category = when (target) {
                        is PromptTarget.AgentTarget -> "agent"
                        is PromptTarget.ToolSkillTarget -> target.item.category
                    }
                    val result = repository.importSinglePromptJson(json, category)
                    withContext(Dispatchers.Main) {
                        refreshTrigger++
                        val summary = "导入: ${result.agents} Agent, ${result.tools} Tool/Skill"
                        showToast(
                            if (result.errors.isEmpty()) summary
                            else summary + "\n" + result.errors.take(3).joinToString("\n")
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { showToast("导入失败: ${e.message}") }
                }
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        agentsList = repository.getAllAgentPrompts()
        toolSkillList = repository.getAllToolSkillPrompts()
        feedbackList = repository.getAllFeedback()
    }

    LaunchedEffect(Unit) {
        AccessLevelManager.refresh(context)
    }

    // 顶层分区：能力（Agents/Tools/Skills）与 反馈（建议/吐槽）分开
    var mainSection by remember { mutableStateOf("能力") }
    var selectedFilter by remember { mutableStateOf("全部") }
    var searchQuery by remember { mutableStateOf("") }
    fun matchesSearch(vararg fields: String): Boolean {
        val q = searchQuery.trim()
        return q.isEmpty() || fields.any { it.contains(q, ignoreCase = true) }
    }
    val filters = if (mainSection == "能力") {
        listOf("全部", "Agents", "Tools", "Skills")
    } else {
        listOf("建议", "吐槽")
    }
    LaunchedEffect(mainSection) {
        selectedFilter = if (mainSection == "能力") "全部" else "吐槽"
    }
    var feedbackDraftKind by remember { mutableStateOf("complaint") }
    var editingFeedback by remember { mutableStateOf<UserFeedbackEntity?>(null) }

    // 弹窗状态
    var activePromptTarget by remember { mutableStateOf<PromptTarget?>(null) }
    var showAddFeedbackDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var addPromptCategory by remember { mutableStateOf<String?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<PromptTarget?>(null) }

    val availableToolSkills = toolSkillList.filter {
        AccessLevel.satisfies(currentLevel, it.dependenceJson)
    }

    val visibleAgents = if (mainSection == "能力" && (selectedFilter == "全部" || selectedFilter == "Agents")) {
        agentsList.filter {
            matchesSearch(it.name, it.role, it.description, it.prompt)
        }
    } else emptyList()
    val visibleTools = if (mainSection == "能力" && (selectedFilter == "全部" || selectedFilter == "Tools")) {
        availableToolSkills.filter {
            it.category == "tool" &&
                matchesSearch(it.id, it.displayName, it.description, it.prompt, it.keywordsJson, it.domain)
        }
    } else emptyList()
    val visibleSkills = if (mainSection == "能力" && (selectedFilter == "全部" || selectedFilter == "Skills")) {
        availableToolSkills.filter {
            it.category == "skill" &&
                matchesSearch(it.id, it.displayName, it.description, it.prompt, it.keywordsJson, it.domain)
        }
    } else emptyList()
    val visibleFeedback = if (mainSection == "反馈") {
        when (selectedFilter) {
            "建议" -> feedbackList.filter { it.kind == "suggestion" && matchesSearch(it.text) }
            else -> feedbackList.filter { it.kind != "suggestion" && matchesSearch(it.text) }
        }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题和添加按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "能力与 Prompt 管理",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "${agentsList.size} 个 Agent，${availableToolSkills.size}/${toolSkillList.size} 个 Tool/Skill 可用 · 当前等级 ${currentLevel.label}",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }

            // 右上角“+”菜单
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "菜单"
                    )
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("🧠 添加 Agent") },
                        onClick = { showAddMenu = false; addPromptCategory = "agent" }
                    )
                    DropdownMenuItem(
                        text = { Text("🎯 添加 Skill") },
                        onClick = { showAddMenu = false; addPromptCategory = "skill" }
                    )
                    DropdownMenuItem(
                        text = { Text("🔧 添加 Tool") },
                        onClick = { showAddMenu = false; addPromptCategory = "tool" }
                    )
                    DropdownMenuItem(
                        text = { Text("💡 添加建议") },
                        onClick = {
                            showAddMenu = false
                            feedbackDraftKind = "suggestion"
                            showAddFeedbackDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("🙅 添加吐槽") },
                        onClick = {
                            showAddMenu = false
                            feedbackDraftKind = "complaint"
                            showAddFeedbackDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("📥 导入 JSON") },
                        onClick = {
                            showAddMenu = false
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("📤 导出 JSON") },
                        onClick = {
                            showAddMenu = false
                            exportLauncher.launch("roubao-prompts.json")
                        }
                    )
                }
            }
        }

        // 顶层 Tab：能力 / 建议吐槽
        TabRow(
            selectedTabIndex = if (mainSection == "能力") 0 else 1,
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            Tab(
                selected = mainSection == "能力",
                onClick = {
                    mainSection = "能力"
                    selectedFilter = "全部"
                },
                text = { Text("🧠 能力") }
            )
            Tab(
                selected = mainSection == "反馈",
                onClick = {
                    mainSection = "反馈"
                    selectedFilter = "吐槽"
                },
                text = { Text("💬 建议 / 吐槽") }
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("搜索名称、描述、关键词、Prompt…", fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )

        // 分区内过滤标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter) }
                )
            }
        }

        // 内容区域
        if (mainSection == "反馈") {
            FeedbackListView(
                items = visibleFeedback,
                onToggle = { item, enabled ->
                    repository.setFeedbackEnabled(item.id, enabled)
                    refreshTrigger++
                },
                onDelete = { item ->
                    repository.deleteFeedback(item.id)
                    refreshTrigger++
                },
                onEdit = { item -> editingFeedback = item }
            )
        } else {
            UnifiedCapabilitiesList(
                agents = visibleAgents,
                tools = visibleTools,
                skills = visibleSkills,
                onEditAgent = { agent -> activePromptTarget = PromptTarget.AgentTarget(agent) },
                onDeleteAgent = { agent -> deleteConfirmTarget = PromptTarget.AgentTarget(agent) },
                onToggleAgent = { agent, enabled ->
                    repository.updateAgentEnabled(agent.id, enabled)
                    refreshTrigger++
                },
                onEditTool = { item -> activePromptTarget = PromptTarget.ToolSkillTarget(item) },
                onDeleteTool = { item -> deleteConfirmTarget = PromptTarget.ToolSkillTarget(item) },
                onToggleTool = { item, enabled ->
                    repository.updateToolSkillEnabled(item.id, enabled)
                    refreshTrigger++
                },
                onExportAgent = { agent ->
                    singleExportTarget = PromptTarget.AgentTarget(agent)
                    singleExportLauncher.launch("agent_${agent.id}.json")
                },
                onImportAgent = { agent ->
                    singleImportTarget = PromptTarget.AgentTarget(agent)
                    singleImportLauncher.launch(arrayOf("application/json", "text/plain"))
                },
                onExportTool = { item ->
                    singleExportTarget = PromptTarget.ToolSkillTarget(item)
                    singleExportLauncher.launch("${item.category}_${item.id}.json")
                },
                onImportTool = { item ->
                    singleImportTarget = PromptTarget.ToolSkillTarget(item)
                    singleImportLauncher.launch(arrayOf("application/json", "text/plain"))
                }
            )
        }
    }

    // 统一编辑：Agent
    (activePromptTarget as? PromptTarget.AgentTarget)?.let { target ->
        UnifiedPromptDialog(
            category = "agent",
            agent = target.agent,
            onDismiss = { activePromptTarget = null },
            onSaveAgent = { updated ->
                repository.updateAgentDetails(updated)
                refreshTrigger++
                activePromptTarget = null
            }
        )
    }

    // 统一编辑：Tool / Skill
    (activePromptTarget as? PromptTarget.ToolSkillTarget)?.let { target ->
        UnifiedPromptDialog(
            category = target.item.category,
            toolSkill = target.item,
            onDismiss = { activePromptTarget = null },
            onSaveToolSkill = { updated ->
                repository.updateToolSkillDetails(updated)
                refreshTrigger++
                activePromptTarget = null
            }
        )
    }

    // 统一添加：Agent / Skill / Tool
    addPromptCategory?.let { category ->
        when (category) {
            "agent" -> UnifiedPromptDialog(
                category = "agent",
                onDismiss = { addPromptCategory = null },
                onSaveAgent = { newAgent ->
                    repository.addAgentPrompt(newAgent)
                    refreshTrigger++
                    addPromptCategory = null
                }
            )
            else -> UnifiedPromptDialog(
                category = category,
                onDismiss = { addPromptCategory = null },
                onSaveToolSkill = { newItem ->
                    repository.addToolSkillPrompt(newItem)
                    refreshTrigger++
                    addPromptCategory = null
                }
            )
        }
    }

    // 统一添加/编辑：建议 / 吐槽
    if (showAddFeedbackDialog) {
        UnifiedPromptDialog(
            category = feedbackDraftKind,
            onDismiss = { showAddFeedbackDialog = false },
            onSaveFeedback = { text ->
                val result = repository.addFeedback(text, feedbackDraftKind)
                if (result == PromptRepository.FEEDBACK_DUPLICATE) {
                    Toast.makeText(context, "已存在相同反馈，未重复添加", Toast.LENGTH_SHORT).show()
                } else {
                    refreshTrigger++
                    showAddFeedbackDialog = false
                }
            }
        )
    }

    editingFeedback?.let { item ->
        UnifiedPromptDialog(
            category = item.kind,
            feedback = item,
            onDismiss = { editingFeedback = null },
            onSaveFeedback = { text ->
                if (repository.updateFeedback(item.id, text, item.kind)) {
                    refreshTrigger++
                    editingFeedback = null
                } else {
                    Toast.makeText(context, "已有相同反馈，无法保存", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 4. 删除确认弹窗
    deleteConfirmTarget?.let { target ->
        val name = when (target) {
            is PromptTarget.AgentTarget -> target.agent.name
            is PromptTarget.ToolSkillTarget -> target.item.displayName
        }
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = { Text(text = "确认删除") },
            text = { Text(text = "确定要删除 [$name] 吗？只支持删除自定义添加的项，删除后不可恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        when (target) {
                            is PromptTarget.AgentTarget -> repository.deleteAgentPrompt(target.agent.id)
                            is PromptTarget.ToolSkillTarget -> repository.deleteToolSkillPrompt(target.item.id)
                        }
                        refreshTrigger++
                        deleteConfirmTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun UnifiedCapabilitiesList(
    agents: List<AgentPromptEntity>,
    tools: List<ToolSkillPromptEntity>,
    skills: List<ToolSkillPromptEntity>,
    onEditAgent: (AgentPromptEntity) -> Unit,
    onDeleteAgent: (AgentPromptEntity) -> Unit,
    onToggleAgent: (AgentPromptEntity, Boolean) -> Unit,
    onEditTool: (ToolSkillPromptEntity) -> Unit,
    onDeleteTool: (ToolSkillPromptEntity) -> Unit,
    onToggleTool: (ToolSkillPromptEntity, Boolean) -> Unit,
    onExportAgent: (AgentPromptEntity) -> Unit = {},
    onImportAgent: (AgentPromptEntity) -> Unit = {},
    onExportTool: (ToolSkillPromptEntity) -> Unit = {},
    onImportTool: (ToolSkillPromptEntity) -> Unit = {}
) {
    val colors = BaoziTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "unified_intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🧠 Agents / Tools / Skills 统一列表",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "用上方标签过滤。右侧开关控制是否参与运行；关闭 Agent 会跳过对应模型调用，" +
                            "关闭 Tool/Skill 会从工具描述和技能匹配中隐藏。长按 Prompt 可编辑。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        if (agents.isEmpty() && tools.isEmpty() && skills.isEmpty()) {
            item(key = "unified_empty") { EmptyState(message = "暂无相关项") }
        }

        if (agents.isNotEmpty()) {
            item(key = "agents_header") { SectionHeader("🧠 Agents (${agents.size})") }
            items(agents, key = { "agent_${it.id}" }) { agent ->
                AgentCard(
                    agent = agent,
                    onEditPrompt = { onEditAgent(agent) },
                    onDelete = { onDeleteAgent(agent) },
                    onToggleEnabled = { onToggleAgent(agent, it) },
                    onExport = { onExportAgent(agent) },
                    onImport = { onImportAgent(agent) }
                )
            }
        }

        if (tools.isNotEmpty()) {
            item(key = "tools_header") { SectionHeader("🔧 Tools (${tools.size})") }
            items(tools, key = { "tool_${it.id}" }) { item ->
                ToolSkillCard(
                    item = item,
                    onEditPrompt = { onEditTool(item) },
                    onDelete = { onDeleteTool(item) },
                    onToggleEnabled = { onToggleTool(item, it) },
                    onExport = { onExportTool(item) },
                    onImport = { onImportTool(item) }
                )
            }
        }

        if (skills.isNotEmpty()) {
            item(key = "skills_header") { SectionHeader("🎯 Skills (${skills.size})") }
            items(skills, key = { "skill_${it.id}" }) { item ->
                ToolSkillCard(
                    item = item,
                    onEditPrompt = { onEditTool(item) },
                    onDelete = { onDeleteTool(item) },
                    onToggleEnabled = { onToggleTool(item, it) },
                    onExport = { onExportTool(item) },
                    onImport = { onImportTool(item) }
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    val colors = BaoziTheme.colors
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun AgentsListView(
    agents: List<AgentPromptEntity>,
    onEditPrompt: (AgentPromptEntity) -> Unit,
    onDeleteAgent: (AgentPromptEntity) -> Unit,
    onToggleEnabled: (AgentPromptEntity, Boolean) -> Unit = { _, _ -> }
) {
    val colors = BaoziTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 架构说明卡片
        item(key = "arch_intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🧠 Prompt 动态配置与 Agent 协作",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "肉包中的 Agent 和 Tool/Skill Prompt 均保存在数据库中。长按下方任意只读文本框可调出编辑器修改 Prompt 或恢复默认值。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        items(agents, key = { it.id }) { agent ->
            AgentCard(
                agent = agent,
                onEditPrompt = { onEditPrompt(agent) },
                onDelete = { onDeleteAgent(agent) },
                onToggleEnabled = { onToggleEnabled(agent, it) }
            )
        }
    }
}

@Composable
fun FeedbackListView(
    items: List<UserFeedbackEntity>,
    onToggle: (UserFeedbackEntity, Boolean) -> Unit,
    onDelete: (UserFeedbackEntity) -> Unit,
    onEdit: (UserFeedbackEntity) -> Unit
) {
    val colors = BaoziTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "feedback_intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "💡 建议 / 吐槽",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "「建议」是正面提示，「吐槽」是负面教训；启用的条目会分别以 " +
                            "User Suggestions / User Complaints 注入 Manager 与 Executor 的 prompt。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "还没有内容。点右上角「添加建议/吐槽」写一条。",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }
        items(items, key = { it.id }) { item ->
            FeedbackCard(
                item = item,
                onToggle = { onToggle(item, it) },
                onDelete = { onDelete(item) },
                onEdit = { onEdit(item) }
            )
        }
    }
}

@Composable
fun FeedbackCard(
    item: UserFeedbackEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = BaoziTheme.colors
    val isSuggestion = item.kind == "suggestion"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSuggestion) colors.success.copy(alpha = 0.15f)
                            else colors.error.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = if (isSuggestion) "💡" else "🙅", fontSize = 24.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isSuggestion) "建议" else "吐槽",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSuggestion) colors.success.copy(alpha = 0.2f)
                                    else colors.error.copy(alpha = 0.2f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isSuggestion) "正面提示" else "负面教训",
                                fontSize = 10.sp,
                                color = if (isSuggestion) colors.success else colors.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.text,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 4.dp)
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = colors.textSecondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 与 Agent/Tool 卡片相同的可长按编辑 Prompt 预览框
            PromptPreviewBox(
                prompt = item.text,
                onLongPress = onEdit
            )
        }
    }
}

@Composable
fun AgentCard(
    agent: AgentPromptEntity,
    onEditPrompt: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = agent.icon, fontSize = 24.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.secondary.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = agent.role,
                                fontSize = 11.sp,
                                color = colors.secondary
                            )
                        }

                        if (!agent.isDefault) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.primary.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "自定义",
                                    fontSize = 10.sp,
                                    color = colors.primary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = agent.description,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = agent.enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = agent.id != "executor",
                    modifier = Modifier.padding(start = 4.dp)
                )

                // 自定义 Agent 支持删除
                if (!agent.isDefault) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = colors.textHint
                    )
                }
            }

            // 职责展开
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "职责：",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    agent.responsibilities.forEach { responsibility ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(text = "• ", fontSize = 12.sp, color = colors.primary)
                            Text(text = responsibility, fontSize = 12.sp, color = colors.textSecondary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Prompt 只读文本框 Preview
            PromptPreviewBox(
                prompt = agent.prompt,
                onLongPress = onEditPrompt
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onImport) { Text("导入本条") }
                TextButton(onClick = onExport) { Text("导出本条") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSkillsListView(
    items: List<ToolSkillPromptEntity>,
    selectedCategoryFilter: String,
    onFilterSelected: (String) -> Unit,
    onEditPrompt: (ToolSkillPromptEntity) -> Unit,
    onDeleteItem: (ToolSkillPromptEntity) -> Unit,
    onToggleEnabled: (ToolSkillPromptEntity, Boolean) -> Unit = { _, _ -> }
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 分类筛选 Chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("全部", "Tools", "Skills").forEach { filter ->
                FilterChip(
                    selected = selectedCategoryFilter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(filter) }
                )
            }
        }

        if (items.isEmpty()) {
            EmptyState(message = "暂无相关项")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    ToolSkillCard(
                        item = item,
                        onEditPrompt = { onEditPrompt(item) },
                        onDelete = { onDeleteItem(item) },
                        onToggleEnabled = { onToggleEnabled(item, it) }
                    )
                }
            }
        }
    }
}

@Composable
fun ToolSkillCard(
    item: ToolSkillPromptEntity,
    onEditPrompt: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {}
) {
    val colors = BaoziTheme.colors

    val categoryIcon = if (item.category == "tool") "🔧" else "🎯"
    val categoryLabel = if (item.category == "tool") "Tool 工具" else "Skill 技能"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.secondary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = categoryIcon, fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.secondary.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = categoryLabel,
                                fontSize = 10.sp,
                                color = colors.secondary
                            )
                        }

                        if (!item.isDefault) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.primary.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "自定义",
                                    fontSize = 10.sp,
                                    color = colors.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.description,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val deps = AccessLevel.parse(item.dependenceJson)
                    Text(
                        text = "依赖: " + if (deps.isEmpty()) "无（所有等级）" else deps.joinToString(" + ") { it.uppercase() },
                        fontSize = 11.sp,
                        color = if (deps.isEmpty()) colors.textHint else colors.primary
                    )
                }

                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(start = 4.dp)
                )

                if (!item.isDefault) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PromptPreviewBox(
                prompt = item.prompt,
                onLongPress = onEditPrompt
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onImport) { Text("导入本条") }
                TextButton(onClick = onExport) { Text("导出本条") }
            }
        }
    }
}

/**
 * 只读 Prompt 预览框（长按编辑）
 */
@Composable
fun PromptPreviewBox(
    prompt: String,
    onLongPress: () -> Unit
) {
    val colors = BaoziTheme.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Prompt 提示词预览",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary
            )
            Text(
                text = "长按编辑 ✎",
                fontSize = 11.sp,
                color = colors.textHint
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.background)
                .border(1.dp, colors.primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .pointerInput(prompt) {
                    detectTapGestures(
                        onLongPress = { onLongPress() }
                    )
                }
                .padding(12.dp)
        ) {
            Text(
                text = prompt,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * 1. 多行 Prompt 编辑窗口 Dialog
 */
@Composable
fun PromptEditorDialog(
    target: PromptTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResetToDefault: () -> Unit
) {
    val colors = BaoziTheme.colors
    val name = when (target) {
        is PromptTarget.AgentTarget -> target.agent.name
        is PromptTarget.ToolSkillTarget -> target.item.displayName
    }
    val promptIdentifier = when (target) {
        is PromptTarget.AgentTarget -> target.agent.name
        is PromptTarget.ToolSkillTarget -> target.item.name
    }
    val category = when (target) {
        is PromptTarget.AgentTarget -> "agent"
        is PromptTarget.ToolSkillTarget -> target.item.category
    }
    val agentId = (target as? PromptTarget.AgentTarget)?.agent?.id

    val initialPrompt = when (target) {
        is PromptTarget.AgentTarget -> target.agent.prompt
        is PromptTarget.ToolSkillTarget -> target.item.prompt
    }

    var promptValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialPrompt,
                selection = TextRange(initialPrompt.length)
            )
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showPlaceholderPanel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "编辑 Prompt - $name", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                if (category == "agent") {
                    Text(
                        text = if (showPlaceholderPanel) {
                            "点击收起动态占位符面板。"
                        } else {
                            "包含动态占位符（如 {{instruction}} 等）。点击这里展开插入面板。"
                        },
                        fontSize = 11.sp,
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            showPlaceholderPanel = !showPlaceholderPanel
                        }
                    )
                } else {
                    Text(
                        text = "Tool/Skill 不支持动态占位符。格式必须是「工具/技能: 标识符 + 说明: ...」。",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
            }
        },
        text = {
            PromptEditorBody(
                promptValue = promptValue,
                onPromptValueChange = {
                    promptValue = it
                    errorText = null
                },
                errorText = errorText,
                allowPlaceholders = category == "agent",
                showPlaceholderPanel = showPlaceholderPanel,
                onTogglePlaceholderPanel = {
                    showPlaceholderPanel = !showPlaceholderPanel
                },
                fieldHeight = if (showPlaceholderPanel) 170.dp else 260.dp
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onResetToDefault,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "恢复默认",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("恢复默认", fontSize = 12.sp)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val error = validatePromptFormat(promptIdentifier, category, agentId, promptValue.text)
                            if (error != null) {
                                errorText = error
                            } else {
                                onSave(promptValue.text)
                            }
                        }
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    )
}

@Composable
fun PlaceholderPickerPanel(
    lastInserted: String?,
    onInsert: (String) -> Unit,
    onClose: () -> Unit
) {
    val colors = BaoziTheme.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "插入动态占位符",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("收起", fontSize = 11.sp)
                }
            }
            Text(
                "点击条目插入到当前光标/选区；面板会保持打开，可以连续插入。",
                fontSize = 10.sp,
                color = colors.textSecondary
            )
            lastInserted?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "已插入 $it",
                    fontSize = 10.sp,
                    color = colors.success
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(PROMPT_PLACEHOLDERS) { placeholder ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.background)
                            .clickable { onInsert(placeholder.token) }
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                placeholder.token,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                placeholder.purpose,
                                fontSize = 10.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "示例：${placeholder.example}",
                            fontSize = 10.sp,
                            color = colors.textHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PromptEditorBody(
    promptValue: TextFieldValue,
    onPromptValueChange: (TextFieldValue) -> Unit,
    errorText: String?,
    allowPlaceholders: Boolean,
    showPlaceholderPanel: Boolean,
    onTogglePlaceholderPanel: () -> Unit,
    fieldHeight: Dp = 260.dp
) {
    val colors = BaoziTheme.colors
    var lastInserted by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = promptValue,
            onValueChange = onPromptValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(fieldHeight),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            ),
            maxLines = 15
        )

        if (allowPlaceholders && showPlaceholderPanel) {
            Spacer(modifier = Modifier.height(8.dp))
            PlaceholderPickerPanel(
                lastInserted = lastInserted,
                onInsert = { token ->
                    val text = promptValue.text
                    val start = minOf(promptValue.selection.start, promptValue.selection.end)
                        .coerceIn(0, text.length)
                    val end = maxOf(promptValue.selection.start, promptValue.selection.end)
                        .coerceIn(0, text.length)
                    val newText = text.replaceRange(start, end, token)
                    onPromptValueChange(
                        TextFieldValue(
                            text = newText,
                            selection = TextRange(start + token.length)
                        )
                    )
                    lastInserted = token
                },
                onClose = onTogglePlaceholderPanel
            )
        }

        errorText?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, fontSize = 11.sp, color = colors.error)
        }
    }
}

@Composable
fun AddPromptDialog(
    category: String,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        displayName: String,
        icon: String,
        role: String,
        description: String,
        responsibilities: List<String>,
        prompt: String
    ) -> Unit
) {
    val colors = BaoziTheme.colors
    val isAgent = category == "agent"
    val typeLabel = when (category) {
        "agent" -> "Agent"
        "skill" -> "Skill"
        else -> "Tool"
    }

    var name by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🤖") }
    var role by remember { mutableStateOf("自定义助手") }
    var description by remember { mutableStateOf("") }
    var responsibilitiesStr by remember { mutableStateOf("") }
    var promptValue by remember {
        mutableStateOf(
            TextFieldValue(
                if (isAgent) {
                    """You are a helpful Android agent.

### User Request ###
{{instruction}}

### Output ###
Provide step by step actions."""
                } else ""
            )
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var showPlaceholderPanel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("添加 $typeLabel", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                if (isAgent) {
                    Text(
                        text = if (showPlaceholderPanel) {
                            "点击收起动态占位符面板。"
                        } else {
                            "与编辑 Agent 使用同一套 Prompt 面板。点击这里展开占位符。"
                        },
                        fontSize = 11.sp,
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            showPlaceholderPanel = !showPlaceholderPanel
                        }
                    )
                } else {
                    Text(
                        "类型：$typeLabel（由菜单入口决定，这里不再切换）",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; formError = null },
                    label = { Text(if (isAgent) "Agent 名称" else "标识符 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isAgent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = icon,
                            onValueChange = { icon = it },
                            label = { Text("图标") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = role,
                            onValueChange = { role = it },
                            label = { Text("角色/定位") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("显示名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isAgent) {
                    OutlinedTextField(
                        value = responsibilitiesStr,
                        onValueChange = { responsibilitiesStr = it },
                        label = { Text("职责（逗号分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                PromptEditorBody(
                    promptValue = promptValue,
                    onPromptValueChange = {
                        promptValue = it
                        errorText = null
                    },
                    errorText = errorText ?: formError,
                    allowPlaceholders = isAgent,
                    showPlaceholderPanel = showPlaceholderPanel,
                    onTogglePlaceholderPanel = {
                        showPlaceholderPanel = !showPlaceholderPanel
                    },
                    fieldHeight = if (showPlaceholderPanel) 150.dp else 180.dp
                )

                if (!isAgent) {
                    TextButton(
                        onClick = {
                            val identifier = name.ifBlank { "my_${category}" }
                            val desc = description.ifBlank { "自定义 $typeLabel" }
                            promptValue = TextFieldValue(
                                if (category == "tool") {
                                    "工具: $identifier\n说明: $desc\n参数:\n  - query: string (必填) - 查询参数"
                                } else {
                                    "技能: $identifier\n说明: $desc"
                                }
                            )
                            errorText = null
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("插入格式模板", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        name = ""
                        displayName = ""
                        description = ""
                        responsibilitiesStr = ""
                        promptValue = TextFieldValue("")
                        errorText = null
                        formError = null
                    }
                ) {
                    Text("清空", fontSize = 12.sp)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                formError = "名称不能为空"
                                return@Button
                            }
                            if (!isAgent && !name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                                formError = "标识符只能包含字母、数字、下划线，且不能以数字开头"
                                return@Button
                            }
                            val promptError = validatePromptFormat(
                                name = name,
                                category = category,
                                agentId = null,
                                prompt = promptValue.text
                            )
                            if (promptError != null) {
                                errorText = promptError
                            } else {
                                val responsibilities = responsibilitiesStr
                                    .split(Regex("[,，;；\\n]"))
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                onConfirm(
                                    name,
                                    displayName,
                                    icon,
                                    role,
                                    description,
                                    responsibilities,
                                    promptValue.text
                                )
                            }
                        }
                    ) {
                        Text("添加")
                    }
                }
            }
        }
    )
}

/**
 * Agent / Tool / Skill / 建议 / 吐槽 共用的添加、编辑弹窗。
 * 同一分类的添加和编辑必须走同一个 Composable，避免两套界面。
 */
private fun safeJsonArray(text: String): JSONArray = try {
    JSONArray(text.ifBlank { "[]" })
} catch (e: Exception) {
    JSONArray()
}

private fun buildSkillEditorJson(item: ToolSkillPromptEntity?): String {
    val root = JSONObject()
    root.put("keywords", safeJsonArray(item?.keywordsJson ?: "[]"))
    root.put("params", safeJsonArray(item?.paramsJson ?: "[]"))
    root.put("related_apps", safeJsonArray(item?.relatedAppsJson ?: "[]"))
    root.put("prompt_hint", item?.promptHint ?: "")
    root.put("prompt", item?.prompt ?: "")
    root.put("domain", item?.domain ?: "")
    root.put("source", item?.source ?: "custom")
    root.put("executable", item?.executable ?: false)
    return root.toString(2)
}

@Composable
fun UnifiedPromptDialog(
    category: String,
    agent: AgentPromptEntity? = null,
    toolSkill: ToolSkillPromptEntity? = null,
    feedback: UserFeedbackEntity? = null,
    onDismiss: () -> Unit,
    onSaveAgent: ((AgentPromptEntity) -> Unit)? = null,
    onSaveToolSkill: ((ToolSkillPromptEntity) -> Unit)? = null,
    onSaveFeedback: ((String) -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    val isAgent = category == "agent"
    val isFeedback = category == "suggestion" || category == "complaint"
    val isEdit = agent != null || toolSkill != null || feedback != null
    val typeLabel = when (category) {
        "agent" -> "Agent"
        "skill" -> "Skill"
        "tool" -> "Tool"
        "suggestion" -> "建议"
        else -> "吐槽"
    }

    var name by remember { mutableStateOf(agent?.name ?: toolSkill?.name ?: "") }
    var displayName by remember { mutableStateOf(toolSkill?.displayName ?: "") }
    var icon by remember { mutableStateOf(agent?.icon ?: "🤖") }
    var role by remember { mutableStateOf(agent?.role ?: "自定义助手") }
    var description by remember { mutableStateOf(agent?.description ?: toolSkill?.description ?: "") }
    var responsibilitiesStr by remember {
        mutableStateOf(agent?.responsibilities?.joinToString("，") ?: "")
    }
    var feedbackText by remember { mutableStateOf(feedback?.text ?: "") }
    var promptValue by remember {
        mutableStateOf(
            TextFieldValue(
                agent?.prompt ?: toolSkill?.prompt ?: if (isAgent) {
                    """You are a helpful Android agent.

### User Request ###
{{instruction}}

### Output ###
Provide step by step actions."""
                } else ""
            )
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var showPlaceholderPanel by remember { mutableStateOf(false) }
    val isSkill = !isAgent && !isFeedback && category == "skill"
    var skillJson by remember { mutableStateOf(buildSkillEditorJson(toolSkill)) }

    fun save() {
        formError = null
        errorText = null

        if (isFeedback) {
            if (feedbackText.isBlank()) {
                formError = "内容不能为空"
                return
            }
            onSaveFeedback?.invoke(feedbackText.trim())
            return
        }

        if (name.isBlank()) {
            formError = "名称不能为空"
            return
        }
        if (!isAgent && !name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            formError = "标识符只能包含字母、数字、下划线，且不能以数字开头"
            return
        }

        if (isSkill) {
            val json = try {
                JSONObject(skillJson)
            } catch (e: Exception) {
                errorText = "其余字段不是合法 JSON：${e.message}"
                return
            }
            val identifier = toolSkill?.name ?: name
            val jsonPrompt = json.optString("prompt").ifBlank {
                "技能: $identifier\n说明: ${description.ifBlank { "自定义 Skill" }}"
            }
            val promptError = validatePromptFormat(
                name = identifier,
                category = "skill",
                agentId = null,
                prompt = jsonPrompt
            )
            if (promptError != null) {
                errorText = promptError
                return
            }
            onSaveToolSkill?.invoke(
                ToolSkillPromptEntity(
                    id = toolSkill?.id ?: UUID.randomUUID().toString(),
                    name = identifier,
                    displayName = displayName.ifBlank { identifier },
                    category = "skill",
                    description = description,
                    prompt = jsonPrompt,
                    defaultPrompt = toolSkill?.defaultPrompt ?: jsonPrompt,
                    isDefault = toolSkill?.isDefault ?: false,
                    enabled = toolSkill?.enabled ?: true,
                    keywordsJson = json.optJSONArray("keywords")?.toString() ?: "[]",
                    paramsJson = json.optJSONArray("params")?.toString() ?: "[]",
                    relatedAppsJson = json.optJSONArray("related_apps")?.toString() ?: "[]",
                    promptHint = json.optString("prompt_hint", ""),
                    domain = json.optString("domain", ""),
                    source = json.optString("source", toolSkill?.source ?: "custom"),
                    executable = json.optBoolean("executable", toolSkill?.executable ?: false)
                )
            )
            return
        }

        val promptError = validatePromptFormat(
            name = if (isAgent) name else (toolSkill?.name ?: name),
            category = if (isAgent) "agent" else category,
            agentId = agent?.id,
            prompt = promptValue.text
        )
        if (promptError != null) {
            errorText = promptError
            return
        }

        if (isAgent) {
            onSaveAgent?.invoke(
                AgentPromptEntity(
                    id = agent?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    icon = icon.ifBlank { "🤖" },
                    role = role.ifBlank { "自定义角色" },
                    description = description,
                    responsibilities = responsibilitiesStr
                        .split(Regex("[,，;；\\n]"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    prompt = promptValue.text,
                    defaultPrompt = agent?.defaultPrompt ?: promptValue.text,
                    isDefault = agent?.isDefault ?: false,
                    enabled = agent?.enabled ?: true
                )
            )
        } else {
            onSaveToolSkill?.invoke(
                ToolSkillPromptEntity(
                    id = toolSkill?.id ?: UUID.randomUUID().toString(),
                    name = toolSkill?.name ?: name,
                    displayName = displayName.ifBlank { name },
                    category = category,
                    description = description,
                    prompt = promptValue.text,
                    defaultPrompt = toolSkill?.defaultPrompt ?: promptValue.text,
                    isDefault = toolSkill?.isDefault ?: false,
                    enabled = toolSkill?.enabled ?: true
                )
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (isFeedback) {
                        (if (isEdit) "编辑" else "添加") + typeLabel
                    } else {
                        (if (isEdit) "编辑 " else "添加 ") + typeLabel
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                when {
                    isAgent -> Text(
                        text = if (showPlaceholderPanel) {
                            "点击收起动态占位符面板。"
                        } else {
                            "Agent 的添加和编辑使用同一套表单。点击这里展开占位符。"
                        },
                        fontSize = 11.sp,
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            showPlaceholderPanel = !showPlaceholderPanel
                        }
                    )
                    isFeedback -> Text(
                        text = "类型：$typeLabel（由入口决定，不在表单内切换）",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                    else -> Text(
                        text = "类型：$typeLabel（由入口决定，不在表单内切换）",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isFeedback) {
                    Text(
                        text = if (category == "suggestion") {
                            "正面建议会注入 Manager / Executor 的 prompt。"
                        } else {
                            "负面教训会注入 Manager / Executor 的 prompt。"
                        },
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = {
                            feedbackText = it
                            formError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        minLines = 6,
                        placeholder = {
                            Text(if (category == "suggestion") "写一条建议..." else "写一条吐槽或教训...")
                        }
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; formError = null },
                        label = { Text(if (isAgent) "Agent 名称" else "标识符 ID") },
                        singleLine = true,
                        readOnly = isEdit && !isAgent,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isAgent) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = icon,
                                onValueChange = { icon = it },
                                label = { Text("图标") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = role,
                                onValueChange = { role = it },
                                label = { Text("角色/定位") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("显示名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("描述") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isAgent) {
                        OutlinedTextField(
                            value = responsibilitiesStr,
                            onValueChange = { responsibilitiesStr = it },
                            label = { Text("职责（逗号分隔）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isSkill) {
                        OutlinedTextField(
                            value = skillJson,
                            onValueChange = {
                                skillJson = it
                                errorText = null
                            },
                            label = { Text("其余字段（JSON：keywords / params / related_apps / prompt_hint / prompt / domain / source / executable）") },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        )
                        (errorText ?: formError)?.let { message ->
                            Text(message, fontSize = 12.sp, color = colors.error)
                        }
                    } else {
                        PromptEditorBody(
                            promptValue = promptValue,
                            onPromptValueChange = {
                                promptValue = it
                                errorText = null
                            },
                            errorText = errorText ?: formError,
                            allowPlaceholders = isAgent,
                            showPlaceholderPanel = showPlaceholderPanel,
                            onTogglePlaceholderPanel = {
                                showPlaceholderPanel = !showPlaceholderPanel
                            },
                            fieldHeight = if (showPlaceholderPanel) 140.dp else 170.dp
                        )

                        if (!isAgent) {
                            TextButton(
                                onClick = {
                                    val identifier = toolSkill?.name ?: name.ifBlank { "my_${category}" }
                                    val desc = description.ifBlank { "自定义 $typeLabel" }
                                    promptValue = TextFieldValue(
                                        "工具: $identifier\n说明: $desc\n参数:\n  - query: string (必填) - 查询参数"
                                    )
                                    errorText = null
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("插入格式模板", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEdit && !isFeedback) {
                    OutlinedButton(
                        onClick = {
                            promptValue = TextFieldValue(
                                agent?.defaultPrompt ?: toolSkill?.defaultPrompt ?: ""
                            )
                            errorText = null
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("恢复默认", fontSize = 12.sp)
                    }
                } else {
                    TextButton(
                        onClick = {
                            name = ""
                            displayName = ""
                            description = ""
                            responsibilitiesStr = ""
                            feedbackText = ""
                            promptValue = TextFieldValue("")
                            skillJson = buildSkillEditorJson(null)
                            errorText = null
                            formError = null
                        }
                    ) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { save() }) {
                        Text(if (isEdit) "保存" else "添加")
                    }
                }
            }
        }
    )
}

/**
 * 2. 添加 Agent 弹窗（旧版，保留兼容；界面已统一走 UnifiedPromptDialog）
 */
@Composable
fun AddAgentDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String, role: String, description: String, responsibilities: List<String>, prompt: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🤖") }
    var role by remember { mutableStateOf("自定义助手") }
    var description by remember { mutableStateOf("") }
    var responsibilitiesStr by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("You are a helpful Android agent.\n\n### User Request ###\n{{instruction}}\n\n### Output ###\nProvide step by step actions.") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义 Agent", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Agent 名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = icon,
                            onValueChange = { icon = it },
                            label = { Text("图标 Emoji") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = role,
                            onValueChange = { role = it },
                            label = { Text("角色/定位") },
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Agent 简短描述") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = responsibilitiesStr,
                        onValueChange = { responsibilitiesStr = it },
                        label = { Text("职责 (用英文或中文逗号分隔)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt 提示词") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val respList = responsibilitiesStr
                            .split(Regex("[,，;；\n]"))
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        onConfirm(name, icon, role, description, respList, prompt)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 3. 添加 Tool/Skill 弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToolSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (category: String, name: String, displayName: String, description: String, prompt: String) -> Unit,
    initialCategory: String = "tool"
) {
    var category by remember { mutableStateOf(initialCategory) } // "tool" or "skill"
    var name by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义 Tool / Skill", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("类型:", fontWeight = FontWeight.Medium)
                    FilterChip(
                        selected = category == "tool",
                        onClick = { category = "tool" },
                        label = { Text("Tool 工具") }
                    )
                    FilterChip(
                        selected = category == "skill",
                        onClick = { category = "skill" },
                        label = { Text("Skill 技能") }
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("标识符 ID (如 my_custom_tool)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称 (如 自定义天气查询)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt 提示词 / LLM 指令") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(category, name, displayName, description, prompt)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun EmptyState(message: String) {
    val colors = BaoziTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📦",
                fontSize = 64.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = colors.textSecondary
            )
        }
    }
}
