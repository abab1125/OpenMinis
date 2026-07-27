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
| Android | 唯一构建目标（CI 仅构建 Android） | Kotlin, Jetpack Compose, Material 3, Gradle 8.11.1 |
| iOS | 已移除（本 fork 不含 iOS 源码，提交 `8e61467`） | 仅上游主线，本 fork 未保留 |

Android 构建关键配置（`src/android/app/build.gradle.kts`）：
- `compileSdk = 36`, `minSdk = 26`, `targetSdk = 35`
- JDK 17（`sourceCompatibility`/`targetCompatibility` + `jvmTarget = "17"`）
- NDK 28.0.12433566（PRoot 原生构建用）
- 包名 `com.openminis.app`

> 所有原生产物（`src/android/app/src/main/assets/proot-aarch64`、
> `alpine-minirootfs.tar.gz`、`src/android/app/src/main/jniLibs/arm64-v8a/*.so`）
> 都被 `.gitignore` 忽略，由本地/CI 构建脚本生成，**不要**把它们提交进仓库。

## 目录结构（开发常用）

```
OpenMinis/
├── src/android/                     # Android 工程（Gradle，唯一构建目标）
│   ├── app/src/main/java/com/openminis/app/
│   │   ├── data/                    # 数据层（DB / Repository / Model）
│   │   ├── provider/                # LLM provider + Hermes 透传连接层
│   │   │   └── hermes/              # ★ Hermes 透传连接层（9 个文件，独立模块）
│   │   ├── tools/                   # Agent 工具（BookTools / FileTools 等）
│   │   ├── ui/                      # Compose UI（chat / sessions / settings / bookshelf）
│   │   └── MinisApp.kt              # Application 入口
│   └── gradle/wrapper/              # Gradle 8.11.1
├── deps/
│   ├── proot/                       # ★ Git 子模块（OpenMinis/proot fork），不要直接改
│   ├── build_proot.sh               # 交叉编译 PRoot + libtalloc → assets/jniLibs
│   ├── build_lame.sh / build_ffmpeg.sh
│   └── talloc/                      # 由 build_proot.sh 生成，勿手改
├── scripts/prepare_android_sandbox.sh  # 下载 Alpine rootfs + Termux proot → assets
├── .github/workflows/android-build.yml  # CI（构建 + 发版到 Releases latest）
├── BUILDING.md                      # 本地构建指引（必读）
└── HERMES_INTEGRATION.md            # 已删除（旧方案，照做会触发 18+ 处 when 编译失败）
```

> `deps/proot` 是 **git 子模块**，其源码不属于本仓库，改动要回到 OpenMinis/proot。
> 本 fork 只消费它编译出的二进制。

## 本地开发

### 前置条件
- JDK 17、Android SDK（含 `cmdline-tools`）、NDK 28.0.12433566
- 本机若不齐，可直接 push 到 `main` 让 CI 验证编译（见下）

### 构建步骤
```bash
cd src/android
# 1. 构建 PRoot 原生依赖（写入 assets/proot-aarch64 + jniLibs/*.so）
cd ../../deps && bash build_proot.sh
# 2. 下载 Alpine rootfs + Termux proot（写入 assets/alpine-minirootfs.tar.gz）
cd .. && bash scripts/prepare_android_sandbox.sh
# 3. 构建 APK
cd src/android && ./gradlew :app:assembleDebug --no-daemon
```
产物：`src/android/app/build/outputs/apk/debug/*.apk`

> 下载脚本会把 tarball/deb 缓存到 `$HOME/.cache/openminis`（可用
> `OPENMINIS_DL_CACHE` 覆盖），重复构建不再骚扰外网。

## CI 与发版

工作流：`.github/workflows/android-build.yml`

**触发**：push 到 `main`、针对 `main` 的 PR、`workflow_dispatch`（手动）。

**流程**：checkout（含子模块）→ JDK17 → Android SDK → NDK → 构建 PRoot →
下载 rootfs → 恢复 debug keystore → `./gradlew :app:assembleDebug` →
上传 APK 到 Actions artifacts（14 天）→ 发布到 Releases `latest`（覆盖式 prerelease）。

**缓存（提速 + 稳化）**：
- `~/.gradle`：Gradle 依赖与 wrapper。
- `~/.cache/openminis`：Alpine rootfs、Termux proot deb、talloc 源码包（避免外网抖动）。
- `deps/build` + `deps/proot/src` + `deps/talloc`：PRoot 增量编译产物。
  key 绑定 `NDK 版本 + deps/proot 子模块 commit + build_proot.sh` 哈希；
  **不使用 restore-keys 兜底**，防止命中不同 proot 源码的旧缓存而打出错误二进制。

**如何让缓存失效（强制全量重建）**：改动以下任一即换 key →
`NDK_VERSION` 环境变量、`deps/build_proot.sh`、或更新 `deps/proot` 子模块提交。
下载缓存 key 绑定 `prepare_android_sandbox.sh` / `build_proot.sh` 内容。

**签名**：用 Secrets `DEBUG_KEYSTORE_BASE64`（已配置）签名，发布的 APK 可与
旧版覆盖安装；缺失则降级默认 keystore（每次签名不同，需先卸载旧版）。

## 代码约定

1. **Kotlin / Compose 风格**：跟随周围代码的注释密度、命名、惯用法。
   提交信息用 `type: subject`（如 `fix: ...`、`feat: ...`、`ci: ...`、`docs: ...`）。
2. **改动聚焦最小**：定制 fork，尽量小而自洽，避免大面积重构。
3. **ProviderType 枚举**：是"LLM 后端类型"枚举，全项目多处 `when` 穷尽匹配。
   **不要往里塞非 LLM 后端类型**（如 Hermes 透传通道）—会触发 18+ 处 when 编译失败。
   Hermes 透传走独立的 `session.backend` 字段分流，不进 ProviderType / ProviderFactory。
4. **ChatViewModel.kt 很大**（9000+ 行）：用 Edit 务必核对行号，历史上有过
   Edit 匹配错位置误删方法体的事故。建议先 Grep 定位唯一上下文再改。
5. **提交要分逻辑**：Hermes 相关一个 commit、小说工具一个 commit、CI/文档各一个，
   不要混成巨型 commit。
6. **不要碰上游同步冲突点**：iOS 工程与本 fork 定制无关，除非明确要求。

## Hermes 网关透传（本 fork 定制）

**模式 B**：OpenMinis 当 Hermes（Mac 端 agent）的消息通道，消息透传到 Hermes
Gateway，叶赫赫的 skills/memory/tools 全在 Hermes 侧跑，OpenMinis 只当 UI 壳。

**架构**：
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
`/v1/chat/completions`，不能当普通 LLM provider 零代码配（模式 A 走不通）。

## 小说创作工具集（本 fork 定制）

`BookTools.kt` + ChatViewModel 层提供 `book_*` agent 工具，文件型仓库
（`/var/minis/books/{bookId}/`，book.json + chapters/ + outline.md + references/）。

工具清单：
- 始终可用：`book_select` / `book_create` / `book_import` / `book_delete`
- 绑定书后：`book_list_chapters` / `book_read_chapter` / `book_write_chapter` /
  `book_edit_chapter` / `book_delete_chapter` / `book_read_outline` /
  `book_write_outline` / `book_reference` / `book_get_context` / `book_search` /
  `book_load_skill`

## 当前状态与分支策略

- Hermes 网关透传 + 小说创作工具改动已于 `7ae3577` 提交；iOS 源码已移除（提交 `8e61467`）。
- 本 fork 仅构建 Android。
- **分支策略**：本地改完直接 push 到 `main` 即触发 CI 出 `latest` APK（沿用"直接推 main"）。
  需要 review 时再开 feature 分支 + PR。
- 后续改动聚焦最小、单独提交（`type: subject`）。
