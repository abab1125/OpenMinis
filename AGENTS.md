# AGENTS.md

本文件给在本仓库工作的 AI coding agent（ZCode / Claude / Cursor 等）提供项目指引。
请务必读完全文再动手。

## 项目概述

**OpenMinis** 是一个开源的端侧 AI agent 应用（GPLv3），把 Claude / GPT / Gemini
等模型带进原生移动端体验，并给 agent 一台"真正的电脑"：设备内运行的完整 Linux
沙箱、浏览器自动化、可扩展 skills、持久化记忆、深度系统集成。

本仓库是 `OpenMinis/OpenMinis` 的 **fork**（`abab1125/OpenMinis`），在上游基础上做了
定制改造（Hermes 网关透传、小说创作工具集等）。上游是镜像、不接受 PR，所有改动
都在本 fork 内进行。

## 平台与技术栈

| 平台 | 状态 | 技术栈 |
|------|------|--------|
| iOS | 主线 | Swift, Xcode, 自建 iSH 内核（C + Meson/Ninja） |
| Android | 主线（CI 仅构建 Android） | Kotlin, Jetpack Compose, Material 3, Gradle 8.11.1 |

Android 构建关键配置（`src/android/app/build.gradle.kts`）：
- `compileSdk = 36`, `minSdk = 26`, `targetSdk = 35`
- JDK 17（`sourceCompatibility`/`targetCompatibility` + `jvmTarget = "17"`）
- NDK 28.0.12433566（PRoot 原生构建用）
- 包名 `com.openminis.app`

## 目录结构

```
OpenMinis/
├── src/
│   ├── android/          # Android 工程（Gradle）
│   │   ├── app/src/main/java/com/openminis/app/
│   │   │   ├── data/              # 数据层（DB / Repository / Model）
│   │   │   ├── provider/          # LLM provider + Hermes 网关连接层
│   │   │   │   └── hermes/        # ★ Hermes 透传连接层（9 个文件，独立模块）
│   │   │   ├── tools/             # Agent 工具（BookTools / FileTools 等）
│   │   │   ├── ui/                # Compose UI（chat / sessions / settings / bookshelf）
│   │   │   └── MinisApp.kt        # Application 入口
│   │   └── gradle/wrapper/        # Gradle 8.11.1
│   └── ios/              # iOS 工程（Xcode）
├── deps/                 # 原生依赖构建脚本（PRoot / iSH / lame / ffmpeg）
├── scripts/              # 沙箱 rootfs 准备等脚本
├── .github/workflows/    # CI（android-build.yml）
├── BUILDING.md           # 构建指引（必读）
└── HERMES_INTEGRATION.md # ★ Hermes 集成方案文档
```

## 构建与 CI

### 本地构建（Android）

详见 `BUILDING.md`。核心步骤：

```bash
cd src/android
# 1. 先构建 PRoot 原生依赖
cd ../../deps && bash build_proot.sh
# 2. 下载 Alpine rootfs
cd .. && bash scripts/prepare_android_sandbox.sh
# 3. 构建 APK
cd src/android && ./gradlew :app:assembleDebug --no-daemon
```

产物：`src/android/app/build/outputs/apk/debug/*.apk`

> **注意**：本机若没有 JDK 17 + Android SDK + NDK 28，无法本地构建。
> 依赖 CI 验证编译。

### CI（GitHub Actions）

工作流文件：`.github/workflows/android-build.yml`

**触发条件**：
- push 到 `main` 分支
- 针 `main` 的 pull request
- 手动触发（`workflow_dispatch`）

**CI 做什么**：checkout（含子模块）→ JDK 17 → Android SDK → NDK 28 → 构建 PRoot →
下载 Alpine rootfs → `./gradlew :app:assembleDebug` → 上传 APK 到 Actions artifacts
（保留 14 天）→ 发布到 GitHub Releases（tag `latest`，可更新覆盖）。

**手动触发 CI**：push 到 main 即可触发；或在 GitHub 仓库 Actions 页选
"Build Android Debug APK" → Run workflow。

## 代码约定

1. **Kotlin / Compose 风格**：跟随周围代码的注释密度、命名、惯用法。
   提交信息用 `type: subject` 格式（如 `fix: ...`、`feat: ...`）。
2. **改动聚焦最小**：本仓库是定制 fork，改动要尽量小而自洽，避免大面积重构。
3. **不要碰上游同步冲突点**：iOS 工程与本 fork 的定制无关，除非明确要求。
4. **ProviderType 枚举**：是"LLM 后端类型"枚举，全项目多处 `when` 穷尽匹配。
   **不要往里塞非 LLM 后端类型**（如 Hermes 透传通道）--会触发 18+ 处 when 编译失败。
   Hermes 透传走独立的 `session.backend` 字段分流，不进 ProviderType / ProviderFactory。
5. **ChatViewModel.kt 很大**（9000+ 行），用 Edit 时务必核对行号，历史上有过
   Edit 匹配错位置误删方法体的事故。

## Hermes 网关透传（本 fork 定制）

**模式 B**：让 OpenMinis 当 Hermes（Mac 端 agent）的消息通道，消息透传到 Hermes
Gateway，叶赫赫的 skills/memory/tools 全在 Hermes 侧跑，OpenMinis 只当 UI 壳。

**架构**（详见 `HERMES_INTEGRATION.md`）：
```
手机 OpenMinis → HTTPS → 阿里云 nginx(/hermes/api/) → SSH 反向隧道 → 本机 Hermes:8642
```

**代码结构**（低耦合，独立模块）：
- `provider/hermes/` 9 个文件：WebSocket JSON-RPC 连接层（抄自 adebnar/hermes-android，
  精简掉 gated 模式）
- `data/db/ChatSessionEntity`：加 `backend` 列（null=本地 loop，"hermes"=透传）
- `ChatViewModel.sendMessage`：一个 `if (isHermesBackend)` 分流点 → `runHermesTurn`
- `HermesClientHolder`：配置存 EncryptedSharedPreferences（baseUrl + token）
- `ui/settings/HermesGatewaySettingsScreen`：独立配置页（URL + token 表单）

**关键**：Hermes 是 WebSocket RPC 端点（`/api/ws`），**不是** OpenAI 兼容的
`/v1/chat/completions`，所以不能当普通 LLM provider 零代码配（模式 A 走不通）。

## 小说创作工具集（本 fork 定制）

`BookTools.kt` + ChatViewModel 层提供 `book_*` agent 工具，文件型仓库
（`/var/minis/books/{bookId}/`，book.json + chapters/ + outline.md + references/）。

工具清单：
- 始终可用：`book_select` / `book_create` / `book_import` / `book_delete`
- 绑定书后：`book_list_chapters` / `book_read_chapter` / `book_write_chapter` /
  `book_edit_chapter` / `book_delete_chapter` / `book_read_outline` /
  `book_write_outline` / `book_reference` / `book_get_context` / `book_search` /
  `book_load_skill`

## 当前未提交改动

工作区有一批 Hermes 集成 + 小说工具的改动尚未提交（见 `git status`）。提交前
确认改动范围，分逻辑提交（Hermes 一个 commit、小说工具一个 commit）。
