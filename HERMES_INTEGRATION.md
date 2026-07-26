# OpenMinis 连接 Hermes 改造方案

## 1. 需求背景

用户在手机上使用 OpenMinis（开源安卓 AI agent 应用），交互体验好。目标是让 OpenMinis 连接 Mac 上的 Hermes Agent，跟叶赫赫互动。

要求**双模式**共存：
- **模式 A**：OpenMinis 自己的 agent loop，LLM 后端指向 Manifest 智能路由网关
- **模式 B**：OpenMinis 当 Hermes 的消息通道（类似飞书/QQ），消息透传到 Hermes Gateway，叶赫赫的全套能力（skills、memory、tools）在 Hermes 侧运行

## 2. 约束条件

- 本地 Mac 无公网 IP，公网要走阿里云（101.37.119.146）转一层
- 不装别的 APK，只针对 OpenMinis 做改造
- 用 Ponytail（马尾辫）懒人开发模式：找社区现成代码抄

## 3. 整体架构

```
手机 OpenMinis APP
    ↓ HTTPS
阿里云 101.37.119.146:443 (nginx)
    ↓ proxy_pass（按路径分流）
    ├─ /manifest/v1  → Manifest 网关 :3001  （模式A：LLM provider）
    └─ /hermes/api   → SSH 反向隧道   → 本机 Hermes Gateway :8642（模式B：平台通道）
```

## 4. 模式 A：零代码配置（立即可用）

OpenMinis 的 `OpenAIProvider` 已支持自定义 `basePath`，无需改代码。

在 APP 里新建 provider：

| 配置项 | 值 |
|--------|-----|
| 类型 | OpenAI Compatible |
| Base URL | `http://101.37.119.146/manifest/v1` |
| API Key | `manifest`（Manifest Bearer token） |
| Model | `auto` |

OpenMinis 的 agent loop 照常运行，LLM 请求走 Manifest 智能路由（auto/codex/simple/voice/image）。

**局限**：走的是 OpenMinis 自己的 agent loop，不是 Hermes 的。叶赫赫的 skills、memory、tools 用不上。

## 5. 模式 B：Hermes 平台通道（需改代码）

### 5.1 社区现成代码

找到 **adebnar/hermes-android**（纯 Kotlin + Jetpack Compose + Material 3，跟 OpenMinis 技术栈完全一致），连接层可直接移植：

| 文件 | 行数 | 功能 |
|------|------|------|
| `HermesGatewayClient.kt` | 239 | WebSocket 连接管理 + 重连 + RPC |
| `HermesRestApi.kt` | 363 | REST API（发消息、列会话、配置等） |
| `Dtos.kt` | ~200 | 全部数据模型 DTO |
| `GatewayHealth.kt` | ~50 | 健康检查 |
| `CredentialStore.kt` | ~80 | token 存储 |

- 认证方式：`X-Hermes-Session-Token` 请求头
- 连接目标：Hermes Dashboard API（端口 9119 / API Server 8642）
- 源仓库：`https://github.com/adebnar/hermes-android`

### 5.2 OpenMinis 改造清单

**要抄的文件**（从 adebnar/hermes-android 复制到 OpenMinis，改包名 `com.hermes.client` → `com.openminis.app`）：

```
provider/hermes/
├── HermesGatewayClient.kt    ← 抄，改包名
├── HermesRestApi.kt          ← 抄，改包名
├── Dtos.kt                   ← 抄，改包名
├── GatewayHealth.kt          ← 抄，改包名
├── CredentialStore.kt        ← 抄，改包名
└── HermesProvider.kt         ← 新写，~100行胶水代码
```

**要改的现有文件**：

1. **`data/model/ProviderConfig.kt`**
   - `ProviderType` 枚举加 `hermes` 值

2. **`provider/ProviderFactory.kt`**
   - `when(instance.providerType)` 加 `hermes` 分支，创建 `HermesProvider`

3. **新建 `provider/hermes/HermesProvider.kt`**（~100 行胶水）
   - 实现 `LLMProvider` 接口
   - `sendMessage()` → 调 `HermesRestApi.sendMessage()`
   - `streamMessage()` → 调 `HermesGatewayClient` WebSocket 接收流式响应
   - 把 Hermes 的响应格式适配为 OpenMinis 的 `LLMMessage` / `LLMStreamChunk`

### 5.3 HermesProvider 胶水逻辑

```kotlin
class HermesProvider(
    private val baseUrl: String,        // http://101.37.119.146/hermes/api
    private val sessionToken: String,    // X-Hermes-Session-Token
    override var model: LLMModel = LLMModel("hermes", "Hermes Agent")
) : LLMProvider {

    private val restApi = HermesRestApi(baseUrl, sessionToken)
    private val wsClient = HermesGatewayClient(baseUrl, sessionToken)

    override suspend fun sendMessage(
        messages: List<LLMMessage>,
        tools: List<AgentToolDefinition>?
    ): LLMResponse {
        // 1. 用 restApi 创建/复用 session
        // 2. 把 messages 最后一条发给 Hermes
        // 3. 等 Hermes 回复（非流式）
        // 4. 转成 LLMResponse 返回
    }

    override fun streamMessage(
        messages: List<LLMMessage>,
        tools: List<AgentToolDefinition>?
    ): Flow<LLMStreamChunk> = callbackFlow {
        // 1. 用 restApi 发消息
        // 2. 通过 wsClient 监听流式响应
        // 3. 每个 chunk 转成 LLMStreamChunk emit
    }
}
```

核心思想：**旁路 OpenMinis 的 agent loop**。消息直接进 Hermes session，叶赫赫的 skills、memory、tools 全在 Hermes 侧跑。OpenMinis 只是个 UI 壳。

## 6. 阿里云中转配置

### 6.1 nginx 配置

在阿里云 `/etc/nginx/sites-available/` 加一段：

```nginx
# Hermes Gateway API 反向代理
location /hermes/api/ {
    proxy_pass http://127.0.0.1:8642;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # WebSocket 支持
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";

    # 超时设置（agent 可能长时间响应）
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}
```

### 6.2 SSH 反向隧道

本机已有 autossh 框架，加一条隧道：

```bash
autossh -M 0 \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=no \
  -R 8642:127.0.0.1:8642 \
  aliyun
```

这样阿里云 `localhost:8642` → 本机 `8642`，nginx 转发到本机 Hermes API Server。

### 6.3 本机 Hermes 开启 API Server

在 `~/.hermes/config.yaml` 中启用：

```yaml
api_server:
  enabled: true
  port: 8642
  # 生成一个 session token 供 OpenMinis 使用
```

## 7. 工作量评估

| 项目 | 工作量 | 说明 |
|------|--------|------|
| 模式 A 配置 | 5 分钟 | 手机上配 provider，零代码 |
| 抄连接层代码 | ~800 行 | 5 个文件，复制改包名 |
| 写胶水代码 | ~100 行 | HermesProvider 适配 LLMProvider |
| 改 ProviderConfig + Factory | ~20 行 | 加枚举值和工厂分支 |
| 阿里云 nginx + SSH 隧道 | 30 分钟 | 配置 + 验证 |
| 本机 Hermes API Server | 10 分钟 | 开启 + 生成 token |
| 编译 APK | 10 分钟 | Gradle build |

**总计**：抄 ~800 行 + 写 ~120 行 + 配置工作

## 8. 实施步骤

1. **先上模式 A**：手机配 Manifest provider，验证基础链路
2. **本机开 Hermes API Server**：确认 8642 端口可用
3. **阿里云配 nginx + SSH 隧道**：验证公网可达
4. **抄连接层代码**：从 adebnar/hermes-android 移植 5 个文件
5. **写 HermesProvider 胶水**：适配 LLMProvider 接口
6. **改 ProviderConfig + Factory**：注册新 provider 类型
7. **编译 APK**：`./gradlew assembleDebug`
8. **手机安装测试**：验证模式 B 双向通信

## 9. 风险与注意事项

- **代理不稳定**：当前 Clash Party/mihomo 反复假活，长时间操作前需验证代理状态
- **SSH 隧道断线**：用 autossh 保活，但网络抖动时可能短暂中断
- **Hermes API 兼容性**：adebnar/hermes-android 基于 Dashboard API（9119），需确认 API Server（8642）接口一致
- **安全**：阿里云 nginx 暴露 8642 到公网，务必用 session token 认证，建议加 IP 白名单或 HTTPS
- **并发**：OpenMinis 的 agent loop 和 Hermes 的 agent loop 不要同时跑同一 session，避免冲突
