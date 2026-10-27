-- ==============================================================================
-- 2026 企业级全栈 AI 智能体平台 (Enterprise Agent Platform)
-- 生产级标准 DDL 与初始数据脚本 (Production Schema & Initial Data)
-- 兼容数据库: MySQL 8.0+ / H2 Database (MySQL Mode)
-- ==============================================================================

-- 1. 系统持久化配置表 (System Configuration)
CREATE TABLE IF NOT EXISTS system_config (
    config_key VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 智能体主表 (Agent Definition)
CREATE TABLE IF NOT EXISTS agent_definition (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    avatar VARCHAR(64),
    model VARCHAR(64) NOT NULL DEFAULT 'deepseek-chat',
    temperature DOUBLE NOT NULL DEFAULT 0.70,
    system_prompt LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 对话会话表 (Chat Session)
CREATE TABLE IF NOT EXISTS chat_session (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. 对话消息历史表 (Chat Message)
CREATE TABLE IF NOT EXISTS chat_message (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT,
    reasoning TEXT,
    citations_json TEXT,
    tokens INT DEFAULT 0,
    duration VARCHAR(32),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_msg_session (session_id),
    CONSTRAINT fk_chat_session FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. 企业知识库切片表 (Knowledge Chunk)
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id VARCHAR(64) PRIMARY KEY,
    doc_title VARCHAR(128) NOT NULL,
    chunk_index INT NOT NULL DEFAULT 0,
    content LONGTEXT NOT NULL,
    keywords VARCHAR(512),
    category VARCHAR(64) DEFAULT 'ENTERPRISE_CORPUS',
    embedding_json LONGTEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chunk_title (doc_title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. MCP 工具插件注册表 (MCP Tool Registry)
CREATE TABLE IF NOT EXISTS mcp_tool (
    id VARCHAR(64) PRIMARY KEY,
    server_name VARCHAR(64),
    function_name VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    description TEXT,
    category VARCHAR(64) NOT NULL DEFAULT 'BUSINESS',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. 智能体技能目录表 (Agent Skill)
CREATE TABLE IF NOT EXISTS agent_skill (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    badge VARCHAR(64),
    badge_type VARCHAR(32),
    title VARCHAR(128) NOT NULL,
    description TEXT,
    suggested_query TEXT,
    version VARCHAR(32) DEFAULT 'v1.0.0',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. 审计与调用链路追踪表 (Audit Log)
CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(64) PRIMARY KEY,
    log_time VARCHAR(64) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128),
    parameters TEXT,
    model VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    duration VARCHAR(32),
    tokens_in INT DEFAULT 0,
    tokens_out INT DEFAULT 0,
    is_real_usage BOOLEAN DEFAULT FALSE,
    tokens INT DEFAULT 0,
    message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_time (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. 工作流定义主表 (Workflow Definition)
CREATE TABLE IF NOT EXISTS workflow_definition (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    dsl_json LONGTEXT,
    version INT DEFAULT 1,
    status VARCHAR(32) DEFAULT 'ACTIVE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. 工作流运行实例表 (Workflow Instance)
CREATE TABLE IF NOT EXISTS workflow_instance (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    variables_json LONGTEXT,
    error_message TEXT,
    INDEX idx_inst_wf (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. 节点执行明细表 (Node Execution)
CREATE TABLE IF NOT EXISTS node_execution (
    id VARCHAR(64) PRIMARY KEY,
    instance_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    input_json LONGTEXT,
    output_json LONGTEXT,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    INDEX idx_exec_inst (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. 人工介入审批单据表 (Approval Ticket)
CREATE TABLE IF NOT EXISTS approval_ticket (
    id VARCHAR(64) PRIMARY KEY,
    instance_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    ticket_title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    approver VARCHAR(64),
    comment TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    decided_at DATETIME,
    INDEX idx_ticket_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==============================================================================
-- 初始系统基础设施配置 (Infrastructure Configuration Only)
-- ==============================================================================

-- 默认网络拓扑与模型配置
INSERT INTO system_config (config_key, config_value) VALUES
('LLM_NETWORK_MODE', 'LOCAL'),
('LLM_REMOTE_IP', '192.168.1.100'),
('LLM_BASE_URL', 'http://localhost:11434/v1'),
('LLM_MODEL', 'deepseek-r1:8b')
ON DUPLICATE KEY UPDATE config_key=config_key;

