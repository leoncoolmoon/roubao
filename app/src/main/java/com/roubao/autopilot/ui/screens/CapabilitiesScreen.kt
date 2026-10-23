package com.roubao.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.data.AgentPromptEntity
import com.roubao.autopilot.data.PromptRepository
import com.roubao.autopilot.data.ToolSkillPromptEntity
import com.roubao.autopilot.ui.theme.BaoziTheme
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
@Composable
fun CapabilitiesScreen() {
    val colors = BaoziTheme.colors

    val repository = remember { PromptRepository.getInstance() }

    var refreshTrigger by remember { mutableStateOf(0) }

    var agentsList by remember { mutableStateOf<List<AgentPromptEntity>>(emptyList()) }
    var toolSkillList by remember { mutableStateOf<List<ToolSkillPromptEntity>>(emptyList()) }

    LaunchedEffect(refreshTrigger) {
        agentsList = repository.getAllAgentPrompts()
        toolSkillList = repository.getAllToolSkillPrompts()
    }

    // Tab 状态: 0 - Agents, 1 - Tools & Skills
    var selectedTab by remember { mutableStateOf(0) }
    var selectedCategoryFilter by remember { mutableStateOf("全部") } // 全部, Tools, Skills

    // 弹窗状态
    var activePromptTarget by remember { mutableStateOf<PromptTarget?>(null) }
    var showAddAgentDialog by remember { mutableStateOf(false) }
    var showAddToolSkillDialog by remember { mutableStateOf(false) }
    var deleteConfirmTarget by remember { mutableStateOf<PromptTarget?>(null) }

    val filteredToolSkills = when (selectedCategoryFilter) {
        "Tools" -> toolSkillList.filter { it.category == "tool" }
        "Skills" -> toolSkillList.filter { it.category == "skill" }
        else -> toolSkillList
    }

    val tabs = listOf("Agents (${agentsList.size})", "Tools & Skills (${filteredToolSkills.size})")

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
                    text = "${agentsList.size} 个 Agent，${toolSkillList.size} 个 Tool/Skill",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }

            // 添加按钮
            ElevatedButton(
                onClick = {
                    if (selectedTab == 0) {
                        showAddAgentDialog = true
                    } else {
                        showAddToolSkillDialog = true
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = colors.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (selectedTab == 0) "添加 Agent" else "添加 Tool/Skill",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Tab 切换
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) colors.primary else colors.textSecondary
                        )
                    }
                )
            }
        }

        // 内容区域
        when (selectedTab) {
            0 -> AgentsListView(
                agents = agentsList,
                onEditPrompt = { agent -> activePromptTarget = PromptTarget.AgentTarget(agent) },
                onDeleteAgent = { agent -> deleteConfirmTarget = PromptTarget.AgentTarget(agent) }
            )
            1 -> ToolsSkillsListView(
                items = filteredToolSkills,
                selectedCategoryFilter = selectedCategoryFilter,
                onFilterSelected = { selectedCategoryFilter = it },
                onEditPrompt = { item -> activePromptTarget = PromptTarget.ToolSkillTarget(item) },
                onDeleteItem = { item -> deleteConfirmTarget = PromptTarget.ToolSkillTarget(item) }
            )
        }
    }

    // 1. Prompt 编辑弹窗
    activePromptTarget?.let { target ->
        PromptEditorDialog(
            target = target,
            onDismiss = { activePromptTarget = null },
            onSave = { newPrompt ->
                when (target) {
                    is PromptTarget.AgentTarget -> repository.updateAgentPrompt(target.agent.id, newPrompt)
                    is PromptTarget.ToolSkillTarget -> repository.updateToolSkillPrompt(target.item.id, newPrompt)
                }
                refreshTrigger++
                activePromptTarget = null
            },
            onResetToDefault = {
                when (target) {
                    is PromptTarget.AgentTarget -> repository.resetAgentPromptToDefault(target.agent.id)
                    is PromptTarget.ToolSkillTarget -> repository.resetToolSkillPromptToDefault(target.item.id)
                }
                refreshTrigger++
                activePromptTarget = null
            }
        )
    }

    // 2. 添加 Agent 弹窗
    if (showAddAgentDialog) {
        AddAgentDialog(
            onDismiss = { showAddAgentDialog = false },
            onConfirm = { name, icon, role, description, responsibilities, prompt ->
                val newAgent = AgentPromptEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    icon = icon.ifEmpty { "🤖" },
                    role = role.ifEmpty { "自定义角色" },
                    description = description,
                    responsibilities = responsibilities,
                    prompt = prompt,
                    defaultPrompt = prompt,
                    isDefault = false
                )
                repository.addAgentPrompt(newAgent)
                refreshTrigger++
                showAddAgentDialog = false
            }
        )
    }

    // 3. 添加 Tool/Skill 弹窗
    if (showAddToolSkillDialog) {
        AddToolSkillDialog(
            onDismiss = { showAddToolSkillDialog = false },
            onConfirm = { category, name, displayName, description, prompt ->
                val newItem = ToolSkillPromptEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    displayName = displayName.ifEmpty { name },
                    category = category,
                    description = description,
                    prompt = prompt,
                    defaultPrompt = prompt,
                    isDefault = false
                )
                repository.addToolSkillPrompt(newItem)
                refreshTrigger++
                showAddToolSkillDialog = false
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
fun AgentsListView(
    agents: List<AgentPromptEntity>,
    onEditPrompt: (AgentPromptEntity) -> Unit,
    onDeleteAgent: (AgentPromptEntity) -> Unit
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
                onDelete = { onDeleteAgent(agent) }
            )
        }
    }
}

@Composable
fun AgentCard(
    agent: AgentPromptEntity,
    onEditPrompt: () -> Unit,
    onDelete: () -> Unit
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
    onDeleteItem: (ToolSkillPromptEntity) -> Unit
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
                        onDelete = { onDeleteItem(item) }
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
    onDelete: () -> Unit
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
                }

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
    val name = when (target) {
        is PromptTarget.AgentTarget -> target.agent.name
        is PromptTarget.ToolSkillTarget -> target.item.displayName
    }

    val initialPrompt = when (target) {
        is PromptTarget.AgentTarget -> target.agent.prompt
        is PromptTarget.ToolSkillTarget -> target.item.prompt
    }

    var promptText by remember { mutableStateOf(initialPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "编辑 Prompt - $name", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "包含动态占位符（如 {{instruction}} 等），修改后保存生效。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 15
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(promptText) }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onResetToDefault) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "恢复默认",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("恢复默认")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

/**
 * 2. 添加 Agent 弹窗
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
    onConfirm: (category: String, name: String, displayName: String, description: String, prompt: String) -> Unit
) {
    var category by remember { mutableStateOf("tool") } // "tool" or "skill"
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
