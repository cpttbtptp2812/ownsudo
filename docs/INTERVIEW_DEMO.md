# OwnAgent 面试演示指南

> 目标：用 5–8 分钟展示一个真实的 Agent Action Runtime，而不是把 OwnAgent 描述成 Agent Builder。

## 一句话介绍

OwnAgent 是面向开发者的 Agent Action Runtime：Agent 保留自己的模型与决策逻辑，OwnAgent 负责工具动作的策略判定、审批、执行协调、租约、状态持久化和运行追踪。

## 演示链路

1. Python 示例直接请求 OpenAI-compatible Chat Completions API，由模型决定是否调用工具。
2. 示例将模型的工具调用转换成 OwnAgent Action。
3. 后端根据工具注册信息和风险策略决定是否允许执行或要求审批。
4. Local Worker 只执行开发者在本地显式注册的 Python callable；它不会下载或执行服务端传来的代码。
5. Worker 使用 claim / lease / heartbeat / completion 生命周期；执行结果返回给 Agent。
6. SDK 将模型调用与 Action 执行的 Span 写入 Trace API，前端 Run Traces 页面读取真实 Span 与 Provider Usage。

## 现场演示准备

### 后端

- 使用 Java 21。
- 配置本地 MySQL 或项目支持的数据库。
- 按仓库 README 配置数据库连接与 Agent API Key。
- 启动 Spring Boot 服务。

### Python 示例

在 `sdk/python` 下安装：

```bash
python -m pip install -e ".[dev]"
```

配置环境变量（不要把真实 Key 提交到仓库）：

```bash
export OWNAGENT_BASE_URL="http://localhost:8080"
export OWNAGENT_AGENT_API_KEY="..."
export OWNAGENT_TOOL_ADMIN_API_KEY="..."
export OPENAI_API_KEY="..."
export OPENAI_MODEL="..."
# 可选：兼容 OpenAI Chat Completions 的服务地址
export OPENAI_BASE_URL="https://api.openai.com/v1"
```

运行：

```bash
python examples/demo_agent.py
```

### 前端

- 登录前端并配置后端地址与 Agent 凭证。
- 打开 Actions 页面中的 Run Traces。
- 运行 Demo 后刷新，选择对应 Trace，查看模型调用、Action Span、状态、耗时和真实 Token Usage。
- 如果 Provider 没有返回 usage，界面应显示 MISSING；不要把估算值说成真实 Token。

## 面试时重点解释的工程决策

- **为什么不把 Agent 决策放进 OwnAgent？** 让开发者继续使用自己的 Agent 框架、模型和提示词；OwnAgent 专注于动作执行控制面。
- **为什么需要审批？** 工具风险不同；高风险动作不应该因为模型提出调用就直接执行。
- **为什么有 Lease 和 Heartbeat？** Worker 可能崩溃或失联；租约用于协调执行权，过期执行不能靠旧租约继续写入。
- **为什么有 UNKNOWN？** 网络超时不代表外部副作用没发生。盲目重试可能重复扣款、发信或创建资源，因此需要显式对账。
- **Token Usage 为什么允许缺失？** 只接受 Provider 实际返回的 Usage；没有就标记 MISSING，不编造数据。
- **为什么 Worker 不是 Sandbox？** 当前 Local Worker 执行本地注册函数，不提供进程/容器隔离；生产环境需要额外的隔离与凭证边界。

## 当前范围与诚实边界

- 已有：Action Runtime、Python SDK、Local Worker、审批流程、Trace Span 写入 API、Run Traces 查询与展示。
- 当前 Demo 的 LLM 请求由示例直接发往 Provider；OwnAgent 不代理模型请求。
- 当前 Trace Explorer 以 Span 列表为主，尚不是完整的横向瀑布图，也没有自动接入所有第三方 Agent 框架。
- 当前 Python Demo 记录 LLM 和 Action Span；这不等于任意 Agent 框架都已自动埋点。
- 尚需真实凭证与运行环境完成一次端到端验收；不要声称已验证用户自己的 Provider 或部署环境。
- Local Worker 不是 Sandbox；不要把它描述成安全执行不可信代码的环境。

## 建议面试展示顺序

1. 先展示架构边界：Agent 决策层 vs OwnAgent 执行控制层。
2. 跑 Demo，展示模型提出工具调用、Action 执行和工具结果回到模型。
3. 打开 Trace Explorer，解释父子 Span、状态和 Provider Usage。
4. 重点讲审批、租约、UNKNOWN 三个可靠性设计。
5. 主动说明限制与下一步：自动 instrumentation、事件驱动 waterfall、沙箱执行、端到端部署验证。
