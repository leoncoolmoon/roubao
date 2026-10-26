<p align="center">
  <img src="docs/images/logo.png" alt="肉包 Roubao - AI Android Automation" width="120" height="120">
</p>

<h1 align="center">肉包 Roubao</h1>

<p align="center">
  <strong>首款无需电脑的开源 AI 手机自动化助手 | AI Phone Automation Assistant</strong>
</p>

<p align="center">
  基于视觉语言模型 (VLM) · 原生 Android Kotlin · 多 Agent 协作架构
</p>

<p align="center">
  <a href="README_EN.md">English</a> | 简体中文
</p>

<p align="center">
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

Roubao 是一款基于视觉语言模型（VLM）的原生 Android 自动化助手，目标是让“手机上的 AI 自动化”真正落地：不依赖电脑，不依赖复杂 Python 环境，不需要每次手动接 ADB，也不需要给每个动作都写死脚本。

它把自然语言理解、界面理解、动作执行、任务规划与安全约束融合在一起，形成一个可以直接在 Android 设备上运行的 Agent 系统。

你可以把它理解成：

- 一个能“看懂手机界面”的 AI 助手
- 一个能“理解你的指令”的自动化执行器
- 一个能在真实 App 场景中完成复杂任务的 mobile agent

无论是点外卖、打开指定 App、发消息、查资料，还是在复杂 UI 中完成重复操作，Roubao 都致力于把这些动作变成自然语言驱动的智能执行。

---

## 背景：为什么需要 Roubao？

2025 年，AI 手机助手开始进入大众视野，像豆包手机助手等产品已经证明：让 AI 真正介入手机操作，是一个非常有价值的方向。

但大多数方案仍然面临几个现实问题：

- 需要电脑参与执行，门槛高
- 依赖 Python / ADB / 复杂环境配置
- 手机与电脑之间需要持续连接，体验差
- 普通用户很难独立部署和使用
- 很多开源项目仍停留在“脚本封装”而不是“原生智能应用”

Roubao 的目标很简单：

> 让 AI 手机自动化真正做到“一部手机就能用”。

我们没有把核心逻辑放在电脑端，而是把整个系统直接搬到 Android 原生环境中，用 Kotlin 原生实现，结合 Shizuku 获得系统级权限，让 APP 直接在手机上截图、分析、点击、输入、跳转、执行任务。

---

## 为什么选择 Roubao？

### 1. 原生 Android，不是 Python 脚本的“包装层”

市面上大量开源手机自动化项目仍然依赖：

- 电脑端 Python 脚本
- ADB / USB / WiFi 调试连接
- 屏幕截图上传到电脑处理
- 再把控制命令回传给手机

这类方案有明显缺点：

- 复杂度高
- 实时性差
- 依赖环境配置
- 对普通用户不友好

Roubao 完全不同：

- 所有处理都在 Android 本机完成
- 截图、识别、执行、决策都在手机端完成
- 通过 Shizuku 获取系统权限，避免繁琐的 ADB 命令操作
- 具备真正的本地执行体验

### 2. 视觉语言模型驱动的界面理解

Roubao 不只是一个点击器，它是一个“会看屏幕”的智能体：

- 能识别当前页面内容
- 能理解控件和布局结构
- 能根据 UI 状态做下一步决策
- 能在多步任务中动态规划与修正

这使它不仅适合简单脚本，也能胜任更复杂的实际应用场景。

### 3. Tools + Skills 双层 Agent 架构

受 Claude Code 设计理念启发，Roubao 采用了双层 Agent 结构：

- Tools：底层原子能力，例如打开应用、剪贴板、HTTP、Shell、截图、点击、滑动等
- Skills：高层用户意图映射，例如“点外卖”“导航”“发微信”“生成图片”等

这样做的好处是：

- 逻辑更清晰，扩展更方便
- 可以在高置信度场景直接走快捷路径
- 在复杂场景中依然保留 Agent 的规划与反思能力

### 4. UI 树 + 视觉定位的混合执行策略

Roubao 同时支持：

- Accessibility / UI Tree 结构感知
- 元素 index 点击（Index Mode）
- 视觉坐标点击（Coordinate Mode）
- 自动回退和兜底策略

这意味着它在复杂界面、动态页面和不同设备分辨率下，更具健壮性。

---

## 与同类项目比较

| 特性 | 肉包 | 豆包手机助手 | 其他开源方案 |
|------|------|-------------|--------------|
| 需要电脑 | ❌ 不需要 | ❌ 不需要 | ✅ 大多需要 |
| 需要购买硬件 | ❌ 不需要 | ✅ 需要 3499+ | ❌ 不需要 |
| 原生 Android | ✅ Kotlin | ✅ 原生 | ❌ Python |
| 开源 | ✅ MIT | ❌ 闭源 | ✅ 开源 |
| Skills/Tools 架构 | ✅ 完整 | ❓ 未知 | ❌ 无 |
| UI树/无障碍混合定位 | ✅ 支持 Index/坐标双模式 | ❓ 未知 | ❌ 仅坐标点击 |
| Prompt 编辑器 | ✅ 支持数据库化管理 | ❌ 无法修改 | ❌ 配置文件/硬编码 |
| UI 设计 | ⭐⭐⭐½ | ⭐⭐⭐⭐ | ⭐⭐ |
| 自定义模型 | ✅ Qwen/GPT/Claude/GUIOwl/MAIUI | ❌ 仅豆包 | ✅ 部分支持 |

**结论：**

Roubao 不是“另一个脚本型自动化工具”，而是一套从 Android 原生层到 Agent 层、再到实际业务 Skills 的完整智能自动化框架。

---

## 核心能力

### 🤖 智能 AI Agent

- 基于视觉语言模型（VLM）理解手机界面
- 支持自然语言任务指令
- 能自动分析当前状态并规划下一步动作
- 在复杂场景中具备多轮决策与反思能力

### 🗃️ 统一 Prompt 编辑器与数据库化持久层

- Agent、Tool、Skill 的 Prompt 模板统一持久化到 SQLite
- 支持动态变量占位与 UI 预览
- 支持长按编辑、恢复默认值、增删自定义 Prompt
- 让能力扩展不再依赖写死代码

### ♿ UI 树感知 + 混合定位

- `AutoPilotAccessibilityService` 负责 Dump UI 节点树
- 抽取文本、布局、控件类型与位置
- Index Mode 提高点击稳定性
- 坐标模式作为智能兜底

### ⏰ 定时任务与提醒调度

- 内置 `ReminderScheduler` 与 `ReminderReceiver`
- 支持按照设定时间自动触发提醒任务
- 让自动化不仅限于“单次执行”，还能延伸到日常提醒场景

### 💬 多轮对话与上下文记忆

- 多轮任务上下文持续保留
- 能跟踪执行进度与历史动作
- 提升复杂任务的完成率和稳定性

### 🎨 现代化 UI 与中英文双语支持

- Material 3 设计语言
- 深色/浅色主题适配
- 平滑动画与流程引导
- 支持中英文界面切换

### 🔧 高度可定制的模型与服务商支持

- 支持 Qwen / GPT / Claude / GUIOwl / MAIUI 等模型
- 支持阿里云 / OpenAI / OpenRouter 等 API 服务
- 可动态拉取模型列表并做模糊搜索
- 可配置自定义 API Endpoint，兼容本地模型（如 Ollama、vLLM）

### 🔒 安全性与保护能力

- API Key 使用 AES-256-GCM 加密
- 检测到支付、密码等敏感页面时自动停止
- 执行过程可视化，悬浮窗展示进度
- 支持手动中止任务
- 可选云端崩溃上报，用户可在设置中关闭

---

## Tools / Skills 架构

### Tools 层：原子能力

底层工具集，每个 Tool 完成一个独立动作：

| Tool | 功能 |
|------|------|
| `search_apps` | 智能搜索已安装应用（支持拼音、语义） |
| `open_app` | 打开应用 |
| `deep_link` | 通过 DeepLink 跳转到 App 特定页面 |
| `clipboard` | 读写剪贴板 |
| `shell` | 执行 Shell 命令 |
| `http` | HTTP 请求（调用外部 API） |
| `screenshot` | 截屏 |
| `tap` | 点击屏幕 |
| `swipe` | 滑动屏幕 |
| `type` | 输入文本 |

### Skills 层：用户意图

Skills 面向真实使用场景，将自然语言任务映射为具体操作：

| Skill | 类型 | 描述 |
|-------|------|------|
| 点外卖(小美) | Delegation | 直接打开 AI App 帮你下单 |
| 点外卖(美团) | GUI 自动化 | 在美团 App 中完成下单步骤 |
| 导航(高德) | Delegation | 通过 DeepLink 快速进入导航 |
| 生成图片(即梦) | Delegation | 直接打开 AI 图像生成工具 |
| 发微信 | GUI 自动化 | 自动发送微信消息 |
| 打开 App | Search/Open | 识别并打开目标应用 |

### 两种执行模式

1. **Delegation（委托）**
   - 高置信度匹配时，直接打开具备 AI 能力的 App
   - 例如小美、豆包、即梦、地图等
   - 通常一步直接完成，响应更快

2. **GUI 自动化**
   - 当目标 App 没有 AI 能力时，走标准 Agent 循环
   - 通过截图、分析、点击、滑动、输入逐步完成
   - 具备更强的通用性和适配能力

---

## 工作流：Roubao 是怎么工作的？

```text
用户输入指令
      │
      ▼
┌─────────────────┐
│  Skills 匹配     │ ← 判断任务属于 delegation 还是 GUI 自动化
└─────────────────┘
      │
      ├── 高置信度 Delegation Skill ──▶ 直接 DeepLink / 打开 AI App ──▶ 完成
      │
      ▼
┌─────────────────┐
│ 标准 Agent 循环 │
└─────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────────────────┐
│ 1. 截图 - Shizuku screencap                                │
│ 2. Manager 规划 - VLM 分析当前画面与状态                     │
│ 3. Executor 决策 - 选择下一步动作                           │
│ 4. 执行动作 - tap / swipe / type / open_app / shell         │
│ 5. Reflector 反馈 - 验证结果是否达成目标                     │
│ 6. 反复迭代直到任务完成或触发安全限制                       │
└──────────────────────────────────────────────────────────────┘
```

---

## 技术架构

```text
┌──────────────────────────────────────────────────────────────┐
│                         肉包 App                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                    UI 层 (Compose)                   │   │
│   │          HomeScreen / Settings / History            │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                   Skills 层                          │   │
│   │    SkillManager → 意图识别 → 快速路径 / 标准路径      │   │
│   │    ┌─────────────────────────────────────────────┐  │   │
│   │    │ 点外卖 │ 导航 │ 打车 │ 发微信 │ AI 画图 │ ... │  │   │
│   │    └─────────────────────────────────────────────┘  │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                   Tools 层                           │   │
│   │    ToolManager → 原子能力封装                        │   │
│   │    ┌─────────────────────────────────────────────┐  │   │
│   │    │ search_apps │ open_app │ deep_link │ clipboard │  │
│   │    │ shell │ http │ screenshot │ tap │ swipe │ type │  │
│   │    └─────────────────────────────────────────────┘  │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                  Agent 层                            │   │
│   │    MobileAgent (移植自 MobileAgent-v3)               │   │
│   │    ┌───────────┬───────────┬───────────┬──────────┐ │   │
│   │    │  Manager  │ Executor  │ Reflector │ Notetaker│ │   │
│   │    │  (规划)   │  (执行)   │  (反思)   │  (记录)  │ │   │
│   │    └───────────┴───────────┴───────────┴──────────┘ │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                  VLM Client                          │   │
│   │           Qwen-VL / GPT-4V / Claude                  │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
├────────────────────────────┼────────────────────────────────┤
│                            ▼                                 │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                    Shizuku                           │   │
│   │              System-level Control                    │   │
│   │     screencap │ input tap │ input swipe │ am start  │   │
│   └─────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

---

## 项目结构

```text
app/src/main/java/com/roubao/autopilot/
├── agent/                    # AI Agent 核心 (移植自 MobileAgent-v3)
│   ├── MobileAgent.kt        # Agent 主循环
│   ├── Manager.kt            # 规划 Agent
│   ├── Executor.kt           # 执行 Agent
│   ├── ActionReflector.kt    # 反思 Agent
│   ├── Notetaker.kt          # 笔记 Agent
│   └── InfoPool.kt           # 状态池
│
├── tools/                    # Tools 层 - 原子能力
│   ├── Tool.kt               # Tool 接口定义
│   ├── ToolManager.kt        # 工具管理器
│   ├── SearchAppsTool.kt     # 应用搜索
│   ├── OpenAppTool.kt        # 打开应用
│   ├── DeepLinkTool.kt       # DeepLink 跳转
│   ├── ClipboardTool.kt      # 剪贴板操作
│   ├── ShellTool.kt          # Shell 命令
│   └── HttpTool.kt           # HTTP 请求
│
├── skills/                   # Skills 层 - 用户意图
│   ├── Skill.kt              # Skill 接口定义
│   ├── SkillRegistry.kt      # Skill 注册表
│   └── SkillManager.kt       # Skill 管理器
│
├── controller/               # 设备控制
│   ├── DeviceController.kt   # Shizuku 控制器
│   └── AppScanner.kt         # 应用扫描 (拼音/语义搜索)
│
├── vlm/                      # VLM 客户端
│   └── VLMClient.kt          # API 调用封装
│
├── ui/                       # 用户界面
│   ├── screens/              # 各个页面
│   ├── theme/                # 主题定义
│   └── OverlayService.kt     # 悬浮窗服务
│
├── data/                     # 数据层
│   └── SettingsManager.kt    # 设置管理
│
└── App.kt                    # Application 入口

app/src/main/assets/
└── skills.json               # Skills 配置文件
```

---

## 快速开始

### 前置要求

1. Android 8.0 (API 26) 及以上
2. WiFi 网络（Shizuku 无线调试依赖 WiFi）
3. Shizuku（用于系统级控制权限）
4. VLM API Key（例如阿里云通义千问 / OpenAI 等）

### 1. 安装并启动 Shizuku

Shizuku 是一个开源工具，可让普通应用获得 ADB 级权限，无需 Root。

- [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
- [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases)

两种启动方式：

**无线调试（推荐，Android 11+）**
1. 进入 `设置 > 开发者选项 > 无线调试`
2. 开启无线调试
3. 在 Shizuku App 中选择“无线调试”方式启动

**电脑 ADB**
1. 手机连接电脑，开启 USB 调试
2. 执行：`adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`

### 2. 安装 Roubao

从 [Releases](../../releases) 页面下载最新 APK 并安装。

### 3. 授权与配置

1. 打开 Roubao App
2. 在 Shizuku 中授权 Roubao
3. 在设置中配置你的 API Key

> ⚠️ 这是使用本项目的关键步骤：没有配置 API Key，App 无法调用 VLM 进行界面理解与任务规划。

### 获取 API Key

**阿里云通义千问（推荐国内用户）**
1. 访问 [阿里云百炼平台](https://bailian.console.aliyun.com/)
2. 开通 DashScope 服务
3. 在 API-KEY 管理中创建密钥

**OpenAI（部分地区需要代理）**
1. 访问 [OpenAI Platform](https://platform.openai.com/)
2. 创建 API Key

---

## 使用示例

```text
帮我点个附近好吃的汉堡
打开网易云音乐播放每日推荐
帮我把最后一张照片发送到微博
帮我在美团点一份猪脚饭
打开 B 站看热门视频
帮我整理一下待办事项并发给微信好友
```

这些任务看起来很“像人类操作”，但在底层都会被解析成：理解屏幕状态 → 规划动作 → 执行 → 验证结果。

---

## 路线图

### 已完成

- [x] **原生 Android 实现** - Kotlin 重写 MobileAgent，摆脱 Python 依赖
- [x] **Tools 层** - 原子能力封装（search_apps、deep_link、clipboard、shell、http 等）
- [x] **Skills 层** - 用户意图映射，支持 Delegation 和 GUI 自动化双模式
- [x] **智能应用搜索** - 拼音、语义、分类多维度匹配
- [x] **快速路径** - 高置信度 Skill 直接 DeepLink 跳转
- [x] **统一 Prompt 编辑器** - Prompt 持久化于 SQLite 数据库，支持 UI 预览、长按编辑与恢复默认值
- [x] **无障碍服务与 UI 树感知** - 集成 `AccessibilityService`，实时 Dump UI 节点树
- [x] **无障碍混合模式 (Index Mode + 坐标)** - 优先元素索引点击，并在失败时自动回退到坐标模式
- [x] **定时提醒与任务调度** - 支持通过 `ReminderScheduler` 进行定时触发和提醒调度
- [x] **专用 UI VLM 支持** - 增加对 GUIOwl、MAIUI 等专用 UI 模型的适配支持

### 🚀 规划与开发中

- [ ] **宏脚本系统** - 录制、存储、回放操作序列
- [ ] **设置增强** - 无障碍服务开关、混合模式状态展示与更清晰的引导

### 近期计划

- [ ] **MCP（Model Context Protocol）** - 扩展日历、邮件、文件管理等能力
- [ ] **执行录屏** - 保存任务执行过程视频，方便回顾和调试
- [ ] **更多 Skills** - 扩充内置 Skills，支持用户自定义

### 中期计划

- [ ] **更多设备支持** - 适配更多 Android 设备与定制系统（MIUI、ColorOS、HarmonyOS 等）
- [ ] **本地模型** - 支持在设备端运行小型 VLM，实现离线使用
- [ ] **任务模板** - 保存和分享常用任务

### 长期愿景

- [ ] **多应用协作** - 跨 App 联动完成复杂工作流
- [ ] **智能学习** - 从用户操作习惯中学习，优化执行策略
- [ ] **语音控制** - 语音唤醒与语音指令

---

## 开发

### 环境要求

- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 34

### 构建

```bash
# 克隆仓库
git clone https://github.com/leoncoolmoon/roubao.git
cd roubao

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

---

## 问题反馈

遇到崩溃、异常或功能问题？欢迎通过以下方式反馈：

### 导出日志

1. 打开 Roubao App → 设置
2. 找到「反馈与调试」分���
3. 点击「导出日志」
4. 选择分享方式（微信、邮件等）发送给开发者

### 日志包含的信息

- 设备型号和 Android 版本
- 应用版本号
- 崩溃堆栈信息（如有）
- 操作日志

> 💡 日志文件不会包含你的 API Key 或隐私信息。

### 提交 Issue

请在 [GitHub Issues](https://github.com/leoncoolmoon/roubao/issues) 提交问题，附上：

- 问题描述
- 复现步骤
- 导出的日志文件

---

## 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

---

## 许可证

本项目基于 MIT 许可证开源。详见 [LICENSE](LICENSE) 文件。

---

## 致谢

- [MobileAgent](https://github.com/X-PLUG/MobileAgent) - 阿里达摩院 X-PLUG 团队开源的移动端 Agent 框架，为本项目提供了重要技术参考
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 优秀的 Android 权限管理框架

---

<p align="center">
  Made with ❤️ by Roubao Team
</p>
