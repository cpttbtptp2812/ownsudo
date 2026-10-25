# OwnSudo Engine (企业级 AI Agent 编排与零信任 2PC 执行中枢)

本项目为独立后端工程（位于 `e:\owner\ownsudo`），与前端渲染工程（位于 `e:\owner\builder`）完全解耦，提供高可用、强一致性的 Agent DAG 工作流状态机引擎。

## 核心架构设计

1. **运行时环境**:
   - Java 21 (LTS) 虚拟线程就绪
   - Spring Boot 3.2.5
   - Spring Data JPA + Hibernate 6.x
   - MySQL 8.0 关系型数据库持久化 (运行于腾讯云独立实例 `43.142.74.224:3306`)

2. **核心业务能力**:
   - **DAG 拓扑执行器**: 解析工作流节点拓扑，按依赖顺序异步驱动多阶段推理与工具执行。
   - **零信任人在回路 2PC 双签门禁 (Two-Phase Commit Gate)**:
     - 在高危资金划转或生产数据库 DDL/DML 变更节点自动挂起状态机 (`SUSPENDED_APPROVAL`)。
     - 生成审计工单 (`ApprovalTicket`)，要求合规工程师数字签名审批。
     - 审批决策完成后计算不可篡改的 SHA-256 审计指纹，解挂并推进下游执行。
   - **实时全双工事件流 (SSE, Server-Sent Events)**:
     - 采用 `SseEmitter` 实现流式节点状态广播 (`INSTANCE_STARTED`, `NODE_RUNNING`, `NODE_COMPLETED`, `TICKET_REQUIRED`, `INSTANCE_COMPLETED`)。
     - 前端 React Flow 画布毫秒级同步变色与高亮。
   - **MCP 工具协议与数据库适配**:
     - 封装 Model Context Protocol (MCP) 标准调用规范，直连数据库执行受控 SQL 迁移。

## REST API 核心接口清单

| 接口路径 | 请求方法 | 描述 |
| :--- | :--- | :--- |
| `/api/health` | GET | 引擎与云端 MySQL 存活探针 |
| `/api/workflow/definitions` | GET | 获取全部预设企业工作流 DAG 模板 |
| `/api/workflow/run` | POST | 触发新工作流实例运行，返回 `instanceId` |
| `/api/workflow/instances` | GET | 获取最近执行历史列表 |
| `/api/workflow/instances/{id}` | GET | 查询指定实例详情及所有节点执行明细 |
| `/api/workflow/instances/{id}/stream` | GET | 建立 SSE 实时事件流订阅 |
| `/api/workflow/approvals/pending` | GET | 查询当前待审批的 2PC 门禁工单 |
| `/api/workflow/approvals/{ticketId}/commit` | POST | 提交 2PC 双签决策 (APPROVED / REJECTED) |

## 本地编译与启动

```bash
# 设置 Java 21 环境变量
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# 编译打包
mvn clean package -DskipTests

# 启动服务 (默认监听 8080 端口)
java -jar target/ownsudo-engine-1.0.0.jar
```
