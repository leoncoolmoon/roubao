# Prompt 与反馈重叠审计（第一阶段）

日期：2026-10-24  
代码分支：`feature/unified-prompt-editor-index-reminder`  
基线：`12c9d43`  
设备数据库备份：`dist/pre-phase1-backup-20261024-120451/prompts.db`

## 审计对象

- 设备实际数据库 `prompts.db`，审计时版本 4；迁移后版本 6。
- 4 个内置 Agent Prompt。
- 10 条建议/吐槽反馈；去重后保留 7 条。
- 原始版本参考：`Turbo1123/roubao` `main` `c8a6d7f`。

设备上的 Prompt 不是代码里的 `DEFAULT_*` 常量，而是之前导入的调优预设：

- Manager：1,358 字符
- Executor：3,665 字符
- Reflector：1,019 字符
- Notetaker：351 字符

因此这次迁移只替换“命中旧预设指纹”的 Prompt，避免覆盖真正手改的内容。

## 反馈与 Prompt 重叠

| 反馈 | 类型 | Prompt 中对应规则 | 结论 |
|---|---|---|---|
| 文件/系统任务优先用 shell，不要打开文件管理器 UI | 建议 | Manager 规则 1；Executor Action Priority 1；shell Tool 描述 | 重复 |
| 输入文字优先用 type，不要点键盘中/英 | 建议 | Manager 规则 1/2；Executor Action Priority 2；type Tool 描述 | 重复 |
| 选择文字/移动光标优先用 set_selection，不要长按拖拽 | 建议 | Manager 规则 1；Executor Action Priority 3 | 重复 |
| 翻页优先用 scroll，不要反复 swipe | 建议 | Manager 规则 1；Executor Action Priority 4；swipe Tool 描述 | 重复 |
| 用无障碍 UI 树文本定位，不要靠截图猜坐标 | 建议 | Executor 开头；Hard Rules；tap/screenshot Tool 描述 | 重复 |
| 截图只在需要视觉判断时使用 | 建议 | Manager 规则 3；Executor Hard Rules；screenshot Tool 描述 | 重复 |
| 不要打开文件管理器做文件操作 | 吐槽 | 与第 1 条同义，且 Prompt 已覆盖 | 重复 |
| 不要点键盘中/英切输入法，type 会直接写入 | 吐槽 | 与第 2 条同义，且 Prompt 已覆盖 | 重复 |
| 不要长按拖拽选字，用 set_selection | 吐槽 | 与第 3 条同义，且 Prompt 已覆盖 | 重复 |
| 不要反复点同一个位置；失败两次必须换策略 | 吐槽 | Manager 规则 5；Executor Hard Rules | 重复 |

结论：**旧预设的 10 条反馈全部与默认 Prompt 重叠**，没有独立的用户信息。
但不能直接清空；也不能原样恢复 10 条。去重后保留 **7 条**：

- 保留 4 条吐槽：文件管理器、键盘中/英、长按拖拽、重复点击。
- 保留 3 条建议：翻页 scroll、UI 树定位、截图时机。
- 删除 3 条与吐槽重复的建议：shell 文件操作、type 输入、set_selection。

## 修改策略

1. 去重后的 7 条反馈在 v6 / 新安装中恢复；v7 清理上一版原样恢复带回来的 3 条重复建议。
2. 新增/导入/编辑反馈时按归一化文本精确去重；跨建议/吐槽也去重。
3. 保留 Prompt 里的通用规则，不把安全底线交给可删除的反馈。
4. Manager 增加 `{{phaseGuidance}}`，按首次规划/后续更新注入不同阶段规则。
5. Executor 补回：
   - `### Current Subgoal ###`
   - `#### Sensitive Operations ####`
   - `Available apps: {{installedApps}}`
6. 删除未使用的 `Executor.GUIDELINES`。
7. Agent / Tool / Skill 卡片增加“导入本条 / 导出本条”。

## Prompt 变化

| Agent | 旧设备预设 | 新默认 | 说明 |
|---|---:|---:|---|
| Manager | 1,358 | 1,347 | 保留工具优先规则，阶段规则改为运行时注入 |
| Executor | 3,665 | 2,689 | 合并重复规则，补回子目标/敏感操作/应用列表 |
| Reflector | 1,019 | 1,019 | 保持现有调优版本 |
| Notetaker | 351 | 351 | 保持现有调优版本 |

## v5 / v6 / v7 迁移规则

- v5：只对 `manager / executor / reflector / notetaker` 四个内置 id 替换 Prompt。
- 只有旧 Prompt 的 SHA-256 命中已知旧预设/旧代码默认值时才替换。
- 指纹不匹配的自定义 Prompt 原样保留。
- v5：反馈只做归一化后的**完全重复**去重，不再按“与 Prompt 重叠”删除。
- v6：恢复 7 条去重后的旧预设反馈；已存在同文本的跳过。
- v7：删除上一版原样恢复时带回来的 3 条重复建议。
- 自定义 Agent / Tool / Skill 不变。

## 单条导入 / 导出

- 每个 Agent / Tool / Skill 卡片下方有“导入本条 / 导出本条”。
- 单条文件仍是 `roubao-prompts` 格式，只含一个 `agents` 或 `tools` 元素。
- 单条导入会校验目标类型：Agent 卡片不能导入 Tool/Skill 文件，反之亦然。
- 完整 JSON 导入 / 导出仍保留在右上角菜单。
