<p align="center">
  <img src="docs/images/logo.png" alt="Roubao - AI Android Automation Assistant" width="120" height="120">
</p>

<h1 align="center">Roubao (肉包)</h1>

<p align="center">
  <strong>Open-source AI phone automation without a PC · Permission-aware · Fully database-backed prompts</strong>
</p>

<p align="center">
  Vision-Language Model (VLM) · Native Android Kotlin · Accessibility + Shizuku + Root
</p>

<p align="center">
  English | <a href="README.md">简体中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-1.4.7-blue.svg" alt="Version">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-26-blue.svg" alt="Min SDK">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Language">
</p>

<p align="center">
  <img src="docs/demo/demo.gif" width="280" alt="Demo">
</p>

<p align="center">
  <img src="docs/screenshots/1.jpg" width="180" alt="Home">
  <img src="docs/screenshots/3.jpg" width="180" alt="Capabilities">
  <img src="docs/screenshots/2.jpg" width="180" alt="History">
  <img src="docs/screenshots/4.jpg" width="180" alt="Settings">
</p>

---

## What Is Roubao?

Roubao is a fully open-source, native Android AI phone automation assistant.  
No PC, no cable. Install it, add a model API key, and operate your phone with natural language.

Unlike Shizuku-only solutions, Roubao models the real permission levels a phone can grant:

```text
No privilege / Accessibility (user) < Shizuku ADB (adb) < Root (root)
```

Each level only exposes the Tools and Skills that can actually run, so the model does not waste steps on paths the system will reject.

> Current version: **1.4.7 (versionCode 12)**  
> Recent focus: permission model cleanup, Prompt DB v23, accessibility enhancements, root shell, task pause/reminders, on-demand app inventory.

---

## Status

| Module | Status | Notes |
|---|---|---|
| Accessibility automation | ✅ Done | UI tree, index actions, coordinate calibration, blind-operation fallback |
| Accessibility screenshot | ✅ Done | API 30+ `takeScreenshot`; fallback to shell / tree-only |
| Shizuku ADB | ✅ Done | shell, files, `pm`/`am`/`settings`, screenshot fallback |
| Root shell | ✅ Implemented, needs device validation | Shizuku Root or `su -c`; `root_shell` tool + confirmation setting |
| Three-tier permission filtering | ✅ Done | Tools/Skills follow permission; upward blocked, downward allowed |
| Database-backed prompts | ✅ Done | SQLite v23; agents / tools / skills / feedback / packs / overlays / audit |
| Prompt import/export/editor | ✅ Done | v3 import/export, placeholders, format checks, per-item reset |
| Pause / resume | ✅ Done | Main UI + overlay checkpoints |
| Scheduled reminders | ✅ Done | AlarmManager + notification; exact/inexact modes |
| On-demand app inventory | ✅ Done | System defaults + skill-related apps; `search_apps` for the full list |
| Root shell confirmation | ✅ Done | Settings: "every time" / "allow by default" |
| `su-user` provider | ❌ Not implemented | su-only devices still lack adb-level shell |
| MediaProjection | ❌ Not implemented | API <30 uses tree-only / shell screenshots |
| Macro scripts | ❌ Not implemented | No record/replay yet |
| `SkillManager.execute` legacy path | ⚠️ Not wired into main chain | Skills are prompt context, not script runners |

---

## Key Features

### 🧱 Three-tier permission model

| Level | Source | Capabilities | Default agent strategy |
|---|---|---|---|
| `user` | Accessibility service | UI tree, screenshots, tap/swipe/type, `open_app`, `search_apps` | `basic` |
| `adb` | Shizuku ADB | Adds `shell`, files, `pm`/`am`/`settings`, screenshot fallback | `standard` |
| `root` | `su` or Shizuku Root + advanced mode | Adds `root_shell` (confirmation by default) | `advanced` |

Rules:

- A Tool/Skill's `dependence` is its **minimum permission**. It is available when `minLevel <= currentLevel`.
- **Upward is blocked**: user level cannot see adb/root tools; adb cannot see root tools.
- **Downward is allowed**: root can use adb/user tools.
- Permission filtering is about **prompt economy**, not security. Real enforcement is Android permissions / providers / `ActionGate`.
- **A pack only selects the Agent prompt strategy.** It does not hide Tools or Skills.
- The Capabilities screen shows everything; only insufficient permission is marked.
- Accessibility is a UI provider, not a prerequisite for adb. If accessibility is disconnected while Shizuku ADB is alive, `currentLevel` remains `adb` and shell stays available.

### ♿ Accessibility enhancements

- UI tree dump (text, content description, view id, bounds, clickable/editable)
- Optional **index mode**: `{"action":"click","index":5}`, with text/coordinate fallback
- Text calibration: when the model provides `text`, prefer the accessibility node center
- Gestures: `dispatchGesture` for tap/swipe/long-press/double-tap
- `ACTION_SET_TEXT` writes text directly, independent of IME language state
- `ACTION_SET_SELECTION` for cursor/selection
- `ACTION_SCROLL_*` for paging
- API 30+ accessibility screenshot; fallback to shell / tree-only
- Basic UI tasks work without Shizuku

### 🧠 Tools + Skills

**Tool = an internal command bound to a permission.**

| Tool | Purpose | Minimum level |
|---|---|---|
| `tap` / `swipe` / `type` / `press_key` | UI actions | user |
| `open_app` / `deep_link` | Launch app / deep link | user |
| `search_apps` | Search installed apps (name/package/semantic) | user |
| `http` / `clipboard` | External API / clipboard | user |
| `shell` | ADB shell (files, pm, am, settings, screenshot fallback) | adb |
| `root_shell` | Root shell (su / Shizuku Root) | root |

**Skill = a capability that drives an external app / GUI.**

- A Skill is **not one-to-one with an app**. `related_apps` is a hint.
- If no related app is installed, prefer a similar installed app, web version, or GUI path.
- Only ask the user when no suitable app/GUI path exists.
- `shell_calc` was removed: it was an internal command, not an external GUI skill. The arithmetic rule now lives in the `shell` prompt and a feedback lesson.

### 🗃️ Fully database-backed prompts

- SQLite `prompts.db` v23:
  - `agent_prompts`
  - `tool_skill_prompts`
  - `user_feedback`
  - `prompt_packs`
  - `agent_pack_overlays`
  - `action_audit`
  - `meta`
- First install copies `assets/prompts_seed.db`; existing installs migrate by version.
- Agents, Tools, Skills, feedback and pack overlays are editable in-app.
- v3 import/export, per-item import/export, per-item reset, full reset.
- Prompt editor with placeholder panel and format checks:
  - Agent: required placeholders such as `{{instruction}}`
  - Tool: `工具: name` + `说明:`
  - Skill: `技能: name` + `说明:`
- Feedback is filtered by permission before injection into Manager/Executor.
- Built-in defaults plus custom suggestions/complaints.

### 🧩 On-demand app inventory

- No more dumping 500+ installed app names into every prompt.
- Resident inventory = **system default apps** (browser/dialer/SMS/home/gallery/camera/email/music/calendar/settings/files/assistant) + **installed apps related to enabled Skills**.
- If an app is missing from the list, call `search_apps`:
  - returns app name + package + system flag
  - `open_app` returns the resolved package/candidates instead of failing silently
- This prevents the old "first 50 names alphabetically" truncation that made WeChat or Baidu Netdisk look uninstalled.

### 🎛️ Task control / interaction

- Pause/resume from the main UI and overlay
- Persistent idle overlay (draggable, scalable)
- Inline `ask_user` input with a 5-minute timeout policy
- `take_over` collaboration and sensitive-action confirmation
- Scheduled reminders via AlarmManager + notification, with exact/inexact modes
- Partial wake lock during tasks
- Successful tasks stay in the target app; failed/stopped tasks return to the main UI
- Stale RUNNING history entries are cleaned on next launch

### 🔐 Root / high risk

- `root_shell` is a separate Tool with `dependence = ["root"]`
- Root providers:
  - Shizuku running as Root → ShellService
  - otherwise → `su -c`
- Settings → **Root command confirmation**:
  - **Every time (default)**: confirm before execution, 2-minute timeout cancels
  - **Allow by default**: no per-command confirmation
- `root_shell` appears only with advanced mode + root provider; adb level is rejected by `ActionGate`
- Normal `shell` is not affected by the root confirmation setting

### 🤖 Multi-model / multi-provider

- OpenAI-compatible APIs
- Alibaba Qwen-VL
- OpenAI / OpenRouter / custom endpoints
- GUI-Owl mode
- MAI-UI mode
- Switch models while paused without losing task memory

### 🎨 UI / UX

- Material 3, dark/light themes
- Home presets, attachments, long-press scheduling
- Capabilities: Agents / Tools / Skills / suggestions & complaints
- Prompt preview + long-press editing
- Settings: permission groups, agent pack preview, Shizuku authorization, root confirmation
- Full English and Chinese UI

### 🔒 Safety

- API key encrypted with AES-256-GCM
- Stops or asks for confirmation on payment/password/biometric pages
- High-risk actions can require confirmation; `ActionGate` is fail-closed for missing tools
- Optional cloud crash reporting
- Audit log records level / action / result

---

## Why This Permission Model?

```text
user(0) < adb(1) < root(2)

item.minLevel = max(dependence)      // [] = user
currentLevel  = highest available provider level
available     = item.enabled && item.minLevel <= currentLevel
```

- **No privilege / accessibility**: UI-only; suitable for basic.
- **Shizuku ADB**: adds shell/files/system commands; suitable for standard.
- **Root**: adds root_shell; suitable for advanced.
- **Pack**: selects the Agent prompt strategy only; does not change Tool/Skill availability.
- **Skill-to-app relation**: a hint, not a binding; fall back to similar apps / web / GUI.

The goals are:

1. Do not send tools the current permission cannot execute.
2. Do not dump hundreds of app names into every prompt.
3. Give each permission level an appropriate Agent strategy.
4. Keep real high-risk protection in system permissions + confirmation + audit.

---

## Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│                         Roubao App                            │
├──────────────────────────────────────────────────────────────┤
│  UI (Compose)                                                │
│    Home / Capabilities / History / Settings / Onboarding     │
│    OverlayService (idle / pause / ask_user / confirm)        │
├──────────────────────────────────────────────────────────────┤
│  Prompt layer                                                │
│    PromptRepository (SQLite v23)                             │
│    Agent / Tool / Skill / Feedback / Pack / Overlay / Audit  │
├──────────────────────────────────────────────────────────────┤
│  Skill layer                                                 │
│    SkillManager → DB roster → permission filter → selection  │
│    related apps are hints; otherwise similar app / GUI      │
├──────────────────────────────────────────────────────────────┤
│  Agent layer                                                 │
│    MobileAgent → Manager → Executor → ActionGate             │
│                 → Reflector / Notetaker                      │
│    Tool Policy generated from permission + DB                │
├──────────────────────────────────────────────────────────────┤
│  Execution layer                                             │
│    DeviceController                                          │
│      ├─ AccessibilityService (tree / index / gestures / shot)│
│      ├─ Shizuku ShellService (adb shell / files / settings)  │
│      └─ su -c / Shizuku Root (root_shell)                    │
├──────────────────────────────────────────────────────────────┤
│  VLM Client                                                   │
│    OpenAI-compatible / Qwen-VL / GUI-Owl / MAI-UI            │
└──────────────────────────────────────────────────────────────┘
```

### Workflow

```text
User instruction
  │
  ▼
AccessLevelManager.refresh()
  │  accessibility / Shizuku ADB / su / Shizuku Root
  ▼
SkillManager / PromptRepository
  │  permission-filtered DB skills; related apps are hints
  ▼
MobileAgent loop
  │
  ├─ Accessibility UI tree + screenshot (or tree-only)
  ├─ Manager planning (agent pack)
  ├─ Executor decision
  ├─ ActionGate: permission / provider / enabled
  ├─ DeviceController execution
  │    ├─ accessibility gestures / index / ACTION_SET_TEXT
  │    ├─ shell (Shizuku ADB)
  │    └─ root_shell (Root + confirmation)
  ├─ Reflector
  └─ loop until done / sensitive page / user stop
```

---

## Project Structure

```text
app/src/main/java/com/roubao/autopilot/
├── agent/
│   ├── MobileAgent.kt              # main loop / pause / action dispatch
│   ├── Manager.kt                  # planning agent
│   ├── Executor.kt                 # execution agent
│   ├── ActionReflector.kt          # reflection agent
│   ├── Notetaker.kt                # notes agent
│   ├── InfoPool.kt                 # state pool
│   ├── AccessLevel.kt              # user / adb / root
│   ├── AccessLevelManager.kt       # provider detection & refresh
│   ├── ActionGate.kt               # pre-execution gate
│   ├── CapabilityPromptSupport.kt  # Tool Policy
│   └── PromptPackResolver.kt       # agent pack selection
├── controller/
│   ├── DeviceController.kt         # accessibility / Shizuku / root_shell
│   ├── AppScanner.kt               # full app scan / search
│   └── AppInventory.kt             # default + skill-related inventory
├── data/
│   ├── PromptRepository.kt         # SQLite v23 / import-export / migrations
│   ├── SettingsManager.kt          # settings
│   └── ExecutionHistory.kt         # execution history
├── service/
│   ├── AutoPilotAccessibilityService.kt
│   ├── ShellService.kt             # Shizuku UserService
│   ├── ReminderReceiver.kt
│   └── ReminderScheduler.kt
├── skills/
│   ├── SkillManager.kt             # DB roster / related-app hints
│   └── SkillRegistry.kt            # assets skills.json loader (seed only; not loaded at runtime)
├── tools/                          # Tool interface / ToolManager / built-ins
├── ui/
│   ├── OverlayService.kt           # idle / pause / ask_user / confirm
│   └── screens/                    # Home / Capabilities / Settings / ...
└── vlm/                            # VLM / GUI-Owl / MAI-UI clients

prompt-seed/                        # readable seed source
scripts/build_prompt_seed.py        # builds assets/prompts_seed.db
app/src/main/assets/prompts_seed.db # seed DB (v23)
```

---

## Quick Start

### Requirements

1. Android 8.0 (API 26)+
2. **Accessibility service** — required for basic mode
3. **Shizuku** — required for standard/adb mode (optional)
4. **Root (optional)** — advanced + `root_shell`
5. VLM API key

> Basic mode does not require Shizuku. Only file/system commands, `shell`, and `root_shell` need higher permission.

### Install

1. Download the APK from [Releases](../../releases).
2. Open Roubao.
3. Enable accessibility following the onboarding.
4. Authorize Roubao in Shizuku if you need adb capabilities.
5. Configure API key / provider / model in Settings.

### Recommended setups

| Scenario | Recommendation |
|---|---|
| UI-only automation | Accessibility + basic |
| Files/system commands | Accessibility + Shizuku ADB + standard |
| root_shell | Shizuku Root or su + advanced mode + advanced |
| No accessibility but Shizuku ADB alive | Level stays adb; shell works, UI tools degrade by provider |

### API keys

- Alibaba Bailian / DashScope (recommended in China)
- OpenAI / OpenRouter
- Custom OpenAI-compatible endpoints (Ollama, vLLM, etc.)

---

## Prompt Management & Customization

- Capabilities → Agents / Tools / Skills / suggestions & complaints
- Long-press a prompt preview to edit; reset to default supported
- Top `⋮`: add Agent/Skill/Tool/suggestion/complaint, import JSON, export JSON
- Prompt editor:
  - dynamic placeholder panel
  - required placeholder checks for Agents
  - first-line format checks for Tools/Skills
- Agent strategy packs:
  - `basic`: accessibility-only UI strategy
  - `standard`: ADB/shell-first strategy
  - `advanced`: root strategy
  - runtime selects by permission; the Settings selection is for preview/editing

---

## Build

```bash
git clone https://github.com/leoncoolmoon/roubao.git
cd roubao

# Debug
./gradlew assembleDebug

# Release (unsigned)
./gradlew assembleRelease

# Local signing example
zipalign -p -f 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks custom.keystore --ks-key-alias roubao_key \
  --out roubao-signed.apk app-release-aligned.apk
```

Environment: JDK 17, Android SDK 34, Gradle 8.5.

> If Firebase Crashlytics is enabled, provide `app/google-services.json`; otherwise disable cloud reporting.

---

## Known Limitations

- `su-user` (`su 2000 -c`) provider is not implemented; su-only devices lack standard/adb shell.
- `root_shell` needs validation on a real root / Shizuku Root device; it is not exposed on non-root devices.
- MediaProjection is not implemented; API <30 relies on tree-only / shell screenshots.
- Macro scripts are not implemented.
- `SkillManager.execute` legacy path is not wired into the main chain; Skills are currently prompt context, not script runners.
- `install_app` is a prompt rule, not a dedicated installer.
- App inventory is on-demand; there is no package-change broadcast, so the cache TTL and `search_apps` handle installs/uninstalls.

---

## Bug Reports

Use [GitHub Issues](https://github.com/leoncoolmoon/roubao/issues) and include:

- Problem description
- Steps to reproduce
- Device model / Android version / app version
- Settings → Feedback & Debug → Export logs

Logs do not contain API keys or personal data.

---

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push the branch
5. Open a Pull Request

---

## License

MIT License. See [LICENSE](LICENSE).

---

## Acknowledgments

- [MobileAgent](https://github.com/X-PLUG/MobileAgent) — mobile agent framework reference
- [Shizuku](https://github.com/RikkaApps/Shizuku) — Android permission framework

<p align="center">
  Made with ❤️ by Roubao Team
</p>


