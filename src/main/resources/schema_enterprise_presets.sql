-- ==============================================================================
-- 企业级预设数据脚本 (Enterprise Presets - 可由数据库按需导入)
-- 包含：标准企业业务助手、合规风控流水线（财务、SRE、法务）、企业制度切片
-- 导入后所有数据均支持在界面进行增删改查（CRUD）与自由定制
-- ==============================================================================

-- 1. 预设标准智能体
INSERT INTO agent_definition (id, name, description, avatar, model, temperature, system_prompt, status, created_at, updated_at)
VALUES (
  'agent-default',
  '企业业务智能助手',
  '企业日常业务问答、合规制度检索与多工具协同助理，可自由在后台增删改查绑定知识库与工具。',
  'Bot',
  'deepseek-chat',
  0.70,
  '你是企业业务智能助手。请依据企业制度与数据库事实，用清晰、准确、通俗自然的语言回答用户的问题，辅助完成日常业务处理。',
  'PUBLISHED',
  NOW(),
  NOW()
) ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description);

-- 2. 预设三套生产级标准合规业务流水线（HTTP 节点已连接真实企业级中台集成接口）
INSERT INTO workflow_definition (id, name, category, badge, icon, description, tags, prompt_template, version, enabled, sort_order, topology_json, status, created_at, updated_at)
VALUES
(
  'wf-fin-reimbursement',
  '员工差旅报销初审与限额合规核查',
  '财务合规',
  '合规风控',
  'Briefcase',
  '自动解析报销凭证与明细 → 对标公司差旅制度限额 → 超标金额自动拦截并生成审批工单 → 财务核销中台入账并存证',
  '财务制度, 费用初审, 限额核算, 审批工单',
  '请审核此笔差旅报销申请：员工张伟提交出差北京差旅报销，住宿费850元/间夜，总计报销金额850元。请比对公司差旅手册，核验是否超标。',
  'v2.0.0',
  TRUE,
  1,
  '[{"id":"step-fin-extract","name":"1. 报销凭证参数提取与脱敏","type":"INPUT_EXTRACT","config":{"fields":"applicant, amount, hotel_fee, city","maskSensitive":true}},{"id":"step-fin-rule","name":"2. 差旅合规标准规则校验","type":"RULE_CHECK","config":{"ruleExpr":"hotel_fee <= 600 && amount <= 600","boundDoc":"企业员工差旅报销与合规手册"}},{"id":"step-fin-hitl","name":"3. 财务主管人工复核闸口","type":"HITL_APPROVAL","config":{"approverRole":"财务主管","timeoutSeconds":3600}},{"id":"step-fin-api","name":"4. 财务核销系统接口同步","type":"HTTP_TOOL","config":{"apiUrl":"http://localhost:8080/api/integration/finance/reimbursement-settle","apiMethod":"POST"}},{"id":"step-fin-audit","name":"5. 全流程防篡改审计存证","type":"LLM_REDUCE","config":{"outputFormat":"STRUCTURED_AUDIT"}}]',
  'ACTIVE',
  NOW(),
  NOW()
),
(
  'wf-sre-patrol',
  '生产系统安全巡检与高危变更核准',
  '系统运维',
  '生产安全',
  'ShieldCheck',
  '解析运维指令与目标资源 → 校验物理只读权限白名单 → 检测 DROP/DELETE 危险变更并强行阻断 → 调度工单审批 → 安全网关受控下发',
  '安全规范, 受控执行, 高危阻断, 物理隔离',
  '请检查生产数据库状态：运维人员李强在生产库 prod_user 执行 DROP TABLE users 操作。核对安全规范并判断风险。',
  'v2.1.0',
  TRUE,
  2,
  '[{"id":"step-sre-extract","name":"1. 运维指令解析与意图识别","type":"INPUT_EXTRACT","config":{"fields":"applicant, targetDb, action","maskSensitive":true}},{"id":"step-sre-rule","name":"2. 变更风险等级与规则裁决","type":"RULE_CHECK","config":{"ruleExpr":"action != \'DROP_TABLE\' && action != \'TRUNCATE\' && amount <= 50000"}},{"id":"step-sre-hitl","name":"3. 技术总监高危操作授权闸口","type":"HITL_APPROVAL","config":{"approverRole":"技术总监","timeoutSeconds":1800}},{"id":"step-sre-api","name":"4. 数据库安全网关受控下发","type":"HTTP_TOOL","config":{"apiUrl":"http://localhost:8080/api/integration/sre/safety-gateway","apiMethod":"POST"}},{"id":"step-sre-audit","name":"5. SRE 运维操作日志防篡改存证","type":"LLM_REDUCE","config":{"outputFormat":"STRUCTURED_AUDIT"}}]',
  'ACTIVE',
  NOW(),
  NOW()
),
(
  'wf-legal-contract',
  '商业采购合同穿透合规与付款初审',
  '合同法务',
  '法务合规',
  'FileText',
  '合同核心条款要素提取 → 比对法务知识库标准与账期限制 → 预付款超标预警 → ERP 采购系统数据校验与归档存证',
  '合同审查, 账期核对, 预付款风控, 法务留痕',
  '全面审查商业采购合同与付款事项：向华为技术有限公司采购服务器硬件，合同总金额180000元，预付款比例40%，账期30天。',
  'v2.0.0',
  TRUE,
  3,
  '[{"id":"step-legal-extract","name":"1. 合同文本关键要素抽取","type":"INPUT_EXTRACT","config":{"fields":"partner, totalAmount, prepaymentRatio","maskSensitive":true}},{"id":"step-legal-rule","name":"2. 法务合规规则与账期校验","type":"RULE_CHECK","config":{"ruleExpr":"prepaymentRatio <= 0.3 && totalAmount <= 200000"}},{"id":"step-legal-hitl","name":"3. 法务负责人合规复核签章","type":"HITL_APPROVAL","config":{"approverRole":"法务主管","timeoutSeconds":7200}},{"id":"step-legal-api","name":"4. ERP 采购系统数据校验","type":"HTTP_TOOL","config":{"apiUrl":"http://localhost:8080/api/integration/erp/procurement-sync","apiMethod":"POST"}},{"id":"step-legal-audit","name":"5. 合同合规审查存证归档","type":"LLM_REDUCE","config":{"outputFormat":"STRUCTURED_AUDIT"}}]',
  'ACTIVE',
  NOW(),
  NOW()
) ON DUPLICATE KEY UPDATE name=VALUES(name), topology_json=VALUES(topology_json);

-- 3. 预设企业制度知识切片
INSERT INTO knowledge_chunk (id, doc_title, chunk_index, content, keywords, category, created_at)
VALUES
(
  'chunk-default-fin',
  '企业员工差旅报销与合规手册',
  0,
  '# 企业员工差旅报销制度\n\n## 1. 住宿标准与报销限额\n- 一线城市（北京、上海、广州、深圳）普通员工住宿报销标准上限为 600 元/间夜。\n- 新一线城市及省会城市住宿报销标准上限为 450 元/间夜。\n- 其余城市住宿报销标准上限为 300 元/间夜。\n\n## 2. 交通工具出行规范\n- 跨城交通优先选择高铁二等座或经济舱机票。\n- 严禁未经前置特批私自乘坐商务座或头等舱。\n\n## 3. 超标拦截与审批流程\n- 超标差额部分系统自动拦截预警，需发起二级财务特批流程。\n- 特批未获批准的，超标部分由员工个人自理。',
  '差旅报销, 一线城市, 住宿标准, 超标提醒, 财务审核',
  'FINANCE',
  NOW()
),
(
  'chunk-default-sre',
  '生产系统安全运维规约',
  0,
  '# 生产系统安全运维规约\n\n## 1. 生产数据操作红线\n- 严禁未经管理员审批在生产环境执行无 WHERE 条件的 DELETE 或 UPDATE 变更。\n- 单次批量删除或修改超过 1000 行记录，必须提前备份并提交审批。\n\n## 2. 高危 DDL 操作审批\n- 凡涉及 DROP TABLE、TRUNCATE TABLE、ALTER TABLE 加唯一索引等重度操作，必须通过管理员人工审批放行。\n- 变更必须在业务低峰期（凌晨 00:00 - 06:00）窗口内执行。\n\n## 3. 查询操作受控\n- 数据库查询工具仅限执行受控 SELECT 查询与 EXPLAIN 分析。\n- 单次查询结果上限不得超过 1000 条，禁止 SELECT * 跨大表扫描。',
  'DROP, TRUNCATE, DELETE, 人工审批, 数据安全',
  'SRE_OPS',
  NOW()
),
(
  'chunk-default-legal',
  '企业商务合同与付款风控规范',
  0,
  '# 企业合同与付款风控规约\n\n## 1. 付款金额分级风控\n- 单笔金额超过人民币 50,000 元的支出类合同，严禁直接自动支付，必须附带法务初审意见与复核凭据。\n- 超过人民币 200,000 元的重大采购，必须由财务总监与业务主管双人联合审批。\n\n## 2. 预付款与违约金条款\n- 凡涉及预付款比例超过总标的 30% 的协议，系统必须提醒合规预警并提交法务特批。\n- 合同履约保证金比例不应低于 10%，账期要求不少于 30 个工作日。\n\n## 3. 印章与签署安全\n- 涉及加盖公章与法人章的授权指令，严禁在未完成审批的情况下执行。',
  '合同审核, 单笔付款, 合规审查, 法务审批',
  'LEGAL',
  NOW()
) ON DUPLICATE KEY UPDATE content=VALUES(content), keywords=VALUES(keywords);

