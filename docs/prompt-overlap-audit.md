# Prompt 与反馈重叠审计（第一阶段）

日期：2026-10-24  
代码分支：`feature/unified-prompt-editor-index-reminder`  
基线：`12c9d43`  
设备数据库备份：`dist/pre-phase1-backup-20261024-120451/prompts.db`

## 审计对象

- 设备实际数据库 `prompts.db`，版本 4。
- 4 个内置 Agent Prompt。
- 10 条建议/吐槽反馈。
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

结论：**旧预设的 10 条反馈全部是默认 Prompt 的复述**，没有独立的用户信息。

## 修改策略

1. 旧预设反馈在 v5 迁移时一次性删除。
2. 新增/导入反馈时按归一化文本精确去重；跨建议/吐槽也去重。
3. 保留 Prompt 里的通用规则，不把安全底线交给可删除的反馈。
4. Manager 增加 `{{phaseGuidance}}`，按首次规划/后续更新注入不同阶段规则。
5. Executor 补回：
   - `### Current Subgoal ###`
   - `#### Sensitive Operations ####`
   - `Available apps: {{installedApps}}`
6. 删除未使用的 `Executor.GUIDELINES`。

## Prompt 变化

| Agent | 旧设备预设 | 新默认 | 说明 |
|---|---:|---:|---|
| Manager | 1,358 | 1,347 | 保留工具优先规则，阶段规则改为运行时注入 |
| Executor | 3,665 | 2,689 | 合并重复规则，补回子目标/敏感操作/应用列表 |
| Reflector | 1,019 | 1,019 | 保持现有调优版本 |
| Notetaker | 351 | 351 | 保持现有调优版本 |

## v5 迁移规则

- 只对 `manager / executor / reflector / notetaker` 四个内置 id 生效。
- 只有旧 Prompt 的 SHA-256 命中已知旧预设/旧代码默认值时才替换。
- 指纹不匹配的自定义 Prompt 原样保留。
- 旧预设反馈按已知文案一次性删除。
- 其余反馈按归一化文本去重，保留最新一条。
- 自定义 Agent / Tool / Skill 不变。
