<p align="center">
  <img src="docs/images/logo.png" alt="肉包 Roubao - AI Android Automation" width="120" height="120">
</p>

<h1 align="center">肉包 Roubao</h1>

<p align="center">
  <strong>无需电脑的开源 AI 手机自动化助手 · 权限感知 · Prompt 全数据库化</strong>
</p>

<p align="center">
  视觉语言模型 (VLM) · 原生 Android Kotlin · 无障碍 + Shizuku + Root 三级权限
</p>

<p align="center">
  <a href="README_EN.md">English</a> | 简体中文
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-1.4.6-blue.svg" alt="Version">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-26-blue.svg" alt="Min SDK">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Language">
</p>

<p align="center">
  <img src="docs/demo/demo.gif" width="280" alt="演示">
</p>

<p align="center">
  <img src="docs/screenshots/1.jpg" width="180" alt="首页">
  <img src="docs/screenshots/3.jpg" width="180" alt="能力">
  <img src="docs/screenshots/2.jpg" width="180" alt="执行记录">
  <img src="docs/screenshots/4.jpg" width="180" alt="设置">
</p>

---

## 项目简介

肉包是一个完全开源、原生运行在 Android 上的 AI 手机自动化助手。  
它不需要电脑、不需要数据线，安装后配置模型 API Key 即可用自然语言操作手机。

和只依赖 Shizuku 的方案不同，肉包把手机真正能拿到的权限拆成三层：

```text
无特权 / 无障碍（user） < Shizuku ADB（adb） < Root（root）
```

每一层都只向模型暴露**当前真的能执行**的 Tool / Skill，避免模型把步数浪费在系统必然拒绝的路径上。

> 当前版本：**1.4.6 (versionCode 11)**  
> 本轮重点：权限模型收敛、Prompt DB v23、无障碍增强、Root Shell、任务暂停/提醒、应用清单按需注入。

---

## 完成度

| 模块 | 状态 | 说明 |
|---|---|---|
| 无障碍基础自动化 | ✅ 完成 | UI 树、index 点击/长按/输入/滚动、坐标校准、盲操兜底 |
| 无障碍截图 | ✅ 完成 | API 30+ `takeScreenshot`；失败回退 shell / tree-only |
| Shizuku ADB | ✅ 完成 | shell、文件、`pm`/`am`/`settings`、截图 fallback |
| Root Shell | ✅ 已实现，待真机验证 | Shizuku Root 或 `su -c`；`root_shell` tool + 确认开关 |
| 三级权限 / 按权限过滤 | ✅ 完成 | Tool/Skill 只看权限；向上禁止、向下允许 |
| Prompt 数据库化 | ✅ 完成 | SQLite v23；Agent / Tool / Skill / 反馈 / Pack / Overlay / 审计 |
| Prompt 导入导出 / 编辑器 | ✅ 完成 | v3 导入导出、占位符面板、格式检查、单条重设 |
| 任务暂停 / 继续 | ✅ 完成 | 主界面 + 悬浮窗检查点 |
| 定时提醒 | ✅ 完成 | AlarmManager + 通知；精确/近似模式 |
| 应用清单按需注入 | ✅ 完成 | 系统默认 + Skill 关联；`search_apps` 查全量 |
| Root Shell 确认 | ✅ 完成 | 设置里可选“每次提醒 / 默认通过” |
| `su-user` provider | ❌ 未实现 | su-only 设备的 adb 级 shell 暂不可用 |
| MediaProjection | ❌ 未实现 | API <30 仍以 tree-only / shell 截图为主 |
| 宏脚本 | ❌ 未实现 | 当前没有录制/回放 |
| `SkillManager.execute` 旧路径 | ⚠️ 未接入主链 | Skill 目前主要作为 Agent 上下文，不是脚本执行器 |

---

## 核心特性

### 🧱 三级权限模型（重点）

| 等级 | 来源 | 能做什么 | 默认 Agent 策略 |
|---|---|---|---|
| `user` | 无障碍服务 | UI 树、截图、点击/滑动/输入、`open_app`、`search_apps` | `basic` |
| `adb` | Shizuku ADB | 增加 `shell`、文件、`pm`/`am`/`settings`、截图 fallback | `standard` |
| `root` | `su` 或 Shizuku Root + 高级模式 | 增加 `root_shell`（默认逐次确认） | `advanced` |

规则：

- Tool / Skill 的 `dependence` 是**最低权限要求**；`minLevel <= currentLevel` 才可用。
- **向上越权禁止**：user 环境看不到 adb/root 工具；adb 环境看不到 root 工具。
- **向下越权允许**：root 环境可以使用 adb/user 工具。
- 权限过滤只负责“减少无效 prompt”，真正的系统门禁仍是 Android 权限 / provider / `ActionGate`。
- **Pack 只决定 Agent prompt**，不限制 Tool / Skill 的可用性。
- 能力页始终显示全部 Tool / Skill，只在权限不足时标“权限不足”。
- 无障碍只是 UI provider，不是 adb 的前置条件：无障碍断开、Shizuku ADB 仍在时，`level` 仍是 `adb`，shell 仍可用。

### ♿ 无障碍增强

- UI 元素树 dump（文本、描述、ID、坐标、可点击/可输入）
- 可选 **index 模式**：`{"action":"click","index":5}`，失败自动回退文本/坐标
- 文本校准：模型给 `text` 时优先按无障碍节点中心坐标点击
- 手势：`dispatchGesture` 完成 tap/swipe/long-press/double-tap
- `ACTION_SET_TEXT` 直接写入，不依赖输入法中英文状态
- `ACTION_SET_SELECTION` 设置光标/选区
- `ACTION_SCROLL_*` 翻页
- API 30+ 无障碍截图；失败回退 shell / tree-only
- 无 Shizuku 也能完成纯 UI 任务（basic 模式）

### 🧠 Tools + Skills 双层能力

**Tool = 肉包内部命令，绑定权限。**

| Tool | 作用 | 最低权限 |
|---|---|---|
| `tap` / `swipe` / `type` / `press_key` | UI 操作 | user |
| `open_app` / `deep_link` | 打开应用 / 深链 | user |
| `search_apps` | 搜索已安装应用（名称/包名/语义） | user |
| `http` / `clipboard` | 外部 API / 剪贴板 | user |
| `shell` | ADB Shell（文件、pm、am、settings、截图 fallback） | adb |
| `root_shell` | Root Shell（su / Shizuku Root） | root |

**Skill = 调用外部 App / GUI 的能力。**

- Skill 和 App **不是一对一绑定**；`related_apps` 只是提示。
- 没有相关 App 时，优先找类似已安装 App、网页版或 GUI 路径。
- 实在没有合适路径，才用 `ask_user` 询问用户；不会因为没有某个 App 就直接判 Skill 不可用。
- `shell_calc` 已删除：它本质是内部命令，不是外部 GUI Skill；算术规则改为 `shell` prompt + 反馈注入。

### 🗃️ Prompt 全数据库化

- SQLite `prompts.db` v23：
  - `agent_prompts`
  - `tool_skill_prompts`
  - `user_feedback`
  - `prompt_packs`
  - `agent_pack_overlays`
  - `action_audit`
  - `meta`
- 首次安装从 `assets/prompts_seed.db` 复制种子库；老版本按版本迁移。
- Agent / Tool / Skill / 反馈 / Pack Overlay 都可以在 App 内编辑。
- 支持 v3 导入/导出、单条导入/导出、单条重设、完全重设。
- Prompt 编辑器带动态占位符面板和格式检查：
  - Agent：`{{instruction}}` 等必需占位符
  - Tool：`工具: name` + `说明:`
  - Skill：`技能: name` + `说明:`
- 反馈按权限过滤注入 Manager / Executor。
- 内置默认反馈 + 可自定义建议/吐槽。

### 🧩 应用清单按需注入

- 不再把 500+ 个已安装应用名全部塞进 prompt。
- 常驻清单 = **系统默认应用**（浏览器/电话/短信/桌面/相册/相机/邮件/音乐/日历/设置/文件/助手）+ **当前权限可用 Skill 的已安装关联应用**。
- 需要但不在清单里的 App，调用 `search_apps` 查全量：
  - 返回 `应用名 + 包名 + 是否系统应用`
  - `open_app` 会回传解析到的包名/候选，不再静默失败
- 这样既保留上下文，又避免按名字截断导致“微信/百度网盘明明装了却找不到”。

### 🎛️ 任务控制 / 交互

- 执行中可暂停/继续（主界面 + 悬浮窗）
- 悬浮窗常驻 idle 包子图标、可拖动、可缩放
- `ask_user` 内联输入，5 分钟超时策略
- `take_over` 人机协作、敏感操作确认
- 定时提醒：AlarmManager + 通知；精确/近似模式明确提示
- 任务执行期间持有 partial wake lock
- 成功任务停留在目标 App；失败/停止才回主界面
- 执行历史自动清理上次进程残留的 RUNNING 记录

### 🔐 Root / 高风险

- `root_shell` 作为独立 Tool，`dependence = ["root"]`
- Root provider：
  - Shizuku 以 Root 运行 → 走 ShellService
  - 否则走 `su -c`
- 设置 → **Root 命令确认**：
  - **每次提醒（默认）**：执行前弹确认，2 分钟无响应按取消
  - **默认通过**：不再逐次确认
- 高级模式 + Root provider 才暴露 `root_shell`；adb 环境会被 `ActionGate` 拒绝
- 普通 `shell` 不受 Root 确认开关影响

### 🤖 多模型 / 多服务商

- OpenAI 兼容接口
- 阿里云通义千问
- OpenAI / OpenRouter / 自定义端点
- GUI-Owl 模式
- MAI-UI 模式
- 暂停期间可切换模型，任务内存保留

### 🎨 UI / 体验

- Material 3、深色/浅色主题
- 首页预设任务、附件、长按定时
- 能力页：Agents / Tools / Skills / 建议吐槽
- Prompt 预览 + 长按编辑
- 设置页权限分组、Agent 策略包预览、Shizuku 授权、Root 确认
- 完整中英文界面

### 🔒 安全

- API Key 使用 AES-256-GCM 加密存储
- 支付/密码/生物识别等敏感页面自动停止或请求确认
- 高风险动作可确认；`ActionGate` 对查不到的 Tool 采用 fail-closed
- 可关闭云端崩溃上报
- 审计日志记录等级 / 动作 / 结果

---

## 权限模型：为什么这么设计

```text
user(0) < adb(1) < root(2)

item.minLevel = max(dependence)      // [] = user
currentLevel  = 当前可用 provider 的最高等级
available     = item.enabled && item.minLevel <= currentLevel
```

- **无权限 / 无障碍**：只有 UI 能力；适合 basic。
- **Shizuku ADB**：增加 shell/文件/系统命令；适合 standard。
- **Root**：增加 root_shell；适合 advanced。
- **Pack**：只切换 Agent 的 prompt 策略，不改变 Tool / Skill 可用性。
- **Skill 的 App 关系**：提示，不是绑定；没有相关 App 时用类似 App / 网页 / GUI。

这样做的目的不是“卡死用户”，而是：

1. 不让模型看到当前权限根本执行不了的工具；
2. 不把 500+ 应用名全部塞进 prompt；
3. 让不同权限等级使用各自合适的 Agent 策略；
4. 真正的高风险仍然由系统权限 + 确认 + 审计兜底。

---

## 技术架构

```text
┌──────────────────────────────────────────────────────────────┐
│                         肉包 App                              │
├──────────────────────────────────────────────────────────────┤
│  UI (Compose)                                                │
│    Home / Capabilities / History / Settings / Onboarding     │
│    OverlayService（idle / 暂停 / ask_user / confirm）          │
├──────────────────────────────────────────────────────────────┤
│  Prompt 层                                                    │
│    PromptRepository (SQLite v23)                             │
│    Agent / Tool / Skill / Feedback / Pack / Overlay / Audit  │
├──────────────────────────────────────────────────────────────┤
│  Skill 层                                                     │
│    SkillManager → DB roster → 权限过滤 → Agent 选择 Skill     │
│    相关 App 只作为提示；没有就走类似 App / GUI                │
├──────────────────────────────────────────────────────────────┤
│  Agent 层                                                     │
│    MobileAgent → Manager → Executor → ActionGate             │
│                 → Reflector / Notetaker                      │
│    Tool Policy 由权限 + DB 动态生成                           │
├──────────────────────────────────────────────────────────────┤
│  执行层                                                       │
│    DeviceController                                          │
│      ├─ AccessibilityService（UI 树 / index / 手势 / 截图）    │
│      ├─ Shizuku ShellService（adb shell / 文件 / 系统命令）    │
│      └─ su -c / Shizuku Root（root_shell）                    │
├──────────────────────────────────────────────────────────────┤
│  VLM Client                                                   │
│    OpenAI-compatible / Qwen-VL / GUI-Owl / MAI-UI            │
└──────────────────────────────────────────────────────────────┘
```

### 工作流程

```text
用户指令
  │
  ▼
AccessLevelManager.refresh()
  │  无障碍 / Shizuku ADB / su / Shizuku Root
  ▼
SkillManager / PromptRepository
  │  按权限过滤 DB Skill；相关 App 只做提示
  ▼
MobileAgent 主循环
  │
  ├─ 无障碍 UI 树 + 截图（或 tree-only）
  ├─ Manager 规划（Agent 策略包）
  ├─ Executor 决策
  ├─ ActionGate 权限 / provider / enabled 检查
  ├─ DeviceController 执行
  │    ├─ 无障碍手势 / index / ACTION_SET_TEXT
  │    ├─ shell（Shizuku ADB）
  │    └─ root_shell（Root + 确认）
  ├─ Reflector 反思
  └─ 循环直到完成 / 敏感页 / 用户停止
```

---

## 项目结构

```text
app/src/main/java/com/roubao/autopilot/
├── agent/
│   ├── MobileAgent.kt              # 主循环 / 暂停 / 动作分发
│   ├── Manager.kt                  # 规划 Agent
│   ├── Executor.kt                 # 执行 Agent
│   ├── ActionReflector.kt          # 反思 Agent
│   ├── Notetaker.kt                # 笔记 Agent
│   ├── InfoPool.kt                 # 状态池
│   ├── AccessLevel.kt              # user / adb / root
│   ├── AccessLevelManager.kt       # provider 探测与等级刷新
│   ├── ActionGate.kt               # 执行前门禁
│   ├── CapabilityPromptSupport.kt  # Tool Policy
│   └── PromptPackResolver.kt       # Agent 策略包选择
├── controller/
│   ├── DeviceController.kt         # 无障碍 / Shizuku / root_shell
│   ├── AppScanner.kt               # 全量应用扫描 / 搜索
│   └── AppInventory.kt             # 默认应用 + Skill 关联清单
├── data/
│   ├── PromptRepository.kt         # SQLite v23 / 导入导出 / 迁移
│   ├── SettingsManager.kt          # 设置
│   └── ExecutionHistory.kt         # 执行历史
├── service/
│   ├── AutoPilotAccessibilityService.kt
│   ├── ShellService.kt             # Shizuku UserService
│   ├── ReminderReceiver.kt
│   └── ReminderScheduler.kt
├── skills/
│   ├── SkillManager.kt             # DB roster / 相关应用提示
│   └── SkillRegistry.kt            # assets skills.json loader（仅建库种子，运行时不再加载）
├── tools/                          # Tool 接口 / ToolManager / 内置 Tool
├── ui/
│   ├── OverlayService.kt           # idle / 暂停 / ask_user / confirm
│   └── screens/                    # Home / Capabilities / Settings / ...
└── vlm/                            # VLM / GUI-Owl / MAI-UI 客户端

prompt-seed/                        # 可读种子源
scripts/build_prompt_seed.py        # 生成 assets/prompts_seed.db
app/src/main/assets/prompts_seed.db # 种子 DB（v23）
```

---

## 快速开始

### 前置要求

1. Android 8.0 (API 26)+
2. **无障碍服务**：basic 模式必需
3. **Shizuku**：standard/adb 模式需要（可选）
4. **Root（可选）**：advanced + `root_shell`
5. VLM API Key

> basic 模式不需要 Shizuku；只有文件/系统命令、`shell`、`root_shell` 才需要更高权限。

### 安装

1. 从 [Releases](../../releases) 下载 APK；
2. 安装后打开肉包；
3. 按引导开启无障碍；
4. 需要 adb 能力时，在 Shizuku 中授权肉包；
5. 设置里配置 API Key / 服务商 / 模型。

### 权限建议

| 场景 | 建议 |
|---|---|
| 只做 UI 自动化 | 无障碍 + basic |
| 需要文件/系统命令 | 无障碍 + Shizuku ADB + standard |
| 需要 root_shell | Shizuku Root 或 su + 高级模式 + advanced |
| 没有无障碍但 Shizuku ADB 在 | 等级仍是 adb；shell 可用，UI 工具按 provider 实际能力降级 |

### API Key

- 阿里云百炼 / DashScope（国内推荐）
- OpenAI / OpenRouter
- 自定义 OpenAI 兼容端点（Ollama、vLLM 等）

---

## Prompt 管理与自定义

- 能力页 → Agent / Tool / Skill / 建议吐槽
- 长按 Prompt 预览框编辑；支持恢复默认
- 顶部 `⋮`：添加 Agent / Skill / Tool / 建议 / 吐槽、导入 JSON、导出 JSON
- Prompt 编辑器：
  - 动态占位符面板
  - Agent 必需占位符检查
  - Tool/Skill 首行格式检查
- Agent 策略包：
  - `basic`：仅无障碍 UI 策略
  - `standard`：ADB/shell 优先策略
  - `advanced`：Root 策略
  - 运行时按权限自动选择；设置里的选择用于预览/编辑

---

## 构建

```bash
git clone https://github.com/leoncoolmoon/roubao.git
cd roubao

# Debug
./gradlew assembleDebug

# Release（未签名）
./gradlew assembleRelease

# 本地签名示例
zipalign -p -f 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks custom.keystore --ks-key-alias roubao_key \
  --out roubao-signed.apk app-release-aligned.apk
```

环境：JDK 17、Android SDK 34、Gradle 8.5。

> 首次运行如果使用 Firebase Crashlytics，需要提供 `app/google-services.json`；没有真实配置时可关闭云端上报。

---

## 已知限制

- `su-user`（`su 2000 -c`）provider 未实现：su-only 设备的 standard/adb shell 暂不可用。
- `root_shell` 需要真机 root / Shizuku Root 验证；非 root 设备上不会出现。
- MediaProjection 未接入；API <30 以 tree-only / shell 截图为主。
- 宏脚本系统未实现。
- `SkillManager.execute` 旧路径未接入主链；Skill 目前主要是 Agent 上下文，不是脚本执行器。
- `install_app` 是 prompt 规则，没有独立安装执行器。
- 应用清单按需注入；安装/卸载后由缓存 TTL 和 `search_apps` 兜底，没有包变更广播即时刷新。

---

## 问题反馈

请通过 [GitHub Issues](https://github.com/leoncoolmoon/roubao/issues) 反馈，附：

- 问题描述
- 复现步骤
- 设备型号 / Android 版本 / App 版本
- 设置 → 反馈与调试 → 导出日志

日志不包含 API Key 或个人隐私。

---

## 贡献

1. Fork 本仓库
2. 创建特性分支
3. 提交更改
4. 推送分支
5. 开启 Pull Request

---

## 许可证

MIT License，详见 [LICENSE](LICENSE)。

---

## 致谢

- [MobileAgent](https://github.com/X-PLUG/MobileAgent) — 移动端 Agent 框架参考
- [Shizuku](https://github.com/RikkaApps/Shizuku) — Android 权限管理框架

<p align="center">
  Made with ❤️ by Roubao Team
</p>

