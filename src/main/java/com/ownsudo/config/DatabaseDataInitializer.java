package com.ownsudo.config;

import com.ownsudo.entity.AgentEntity;
import com.ownsudo.entity.KnowledgeChunk;
import com.ownsudo.entity.McpTool;
import com.ownsudo.entity.WorkflowDefinition;
import com.ownsudo.repository.AgentRepository;
import com.ownsudo.repository.KnowledgeChunkRepository;
import com.ownsudo.repository.McpToolRepository;
import com.ownsudo.repository.WorkflowDefinitionRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据库启动初始化类
 * 
 * 严格遵循真实业务持久化原则：
 * 1. 绝不执行 deleteAll() 清空已有数据库；
 * 2. 仅在库中智能体为 0 时注册一个通用的基础智能体；
 * 3. 仅在库中工具为 0 时注册系统受控基础工具；
 * 4. 仅在库中流程定义为 0 时注册标准企业级合规业务流水线（财务、SRE安全、法务合同）；
 * 5. 仅在库中知识库切片为 0 时持久化企业核心制度（差旅标准、运维规约、合同风控）。
 */
@Component
public class DatabaseDataInitializer implements CommandLineRunner {

    private final AgentRepository agentRepository;
    private final McpToolRepository toolRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final KnowledgeChunkRepository knowledgeRepository;

    public DatabaseDataInitializer(
            AgentRepository agentRepository,
            McpToolRepository toolRepository,
            WorkflowDefinitionRepository workflowRepository,
            KnowledgeChunkRepository knowledgeRepository
    ) {
        this.agentRepository = agentRepository;
        this.toolRepository = toolRepository;
        this.workflowRepository = workflowRepository;
        this.knowledgeRepository = knowledgeRepository;
    }

    @Override
    public void run(String... args) {
        initDefaultAgentIfEmpty();
        initSystemToolsIfEmpty();
        initDefaultWorkflowsIfEmpty();
        initDefaultKnowledgeIfEmpty();
    }

    private void initDefaultAgentIfEmpty() {
        if (agentRepository.count() > 0) return;

        AgentEntity a1 = new AgentEntity();
        a1.setId("agent-default");
        a1.setName("企业业务智能助手");
        a1.setDescription("企业日常业务问答与制度检索助理，可按需配置关联知识库与工具。");
        a1.setModel("deepseek-chat");
        a1.setTemperature(0.7);
        a1.setSystemPrompt("你是企业业务智能助手。请用清晰、准确、通俗自然的语言回答用户的问题，辅助完成日常业务处理。");
        a1.setStatus("PUBLISHED");
        a1.setToolsCount(4);
        a1.setKbCount(3);
        a1.setEnableMemory(true);
        a1.setCreatedAt(LocalDateTime.now());
        a1.setUpdatedAt(LocalDateTime.now());
        agentRepository.save(a1);
    }

    private void initSystemToolsIfEmpty() {
        if (toolRepository.count() > 0) return;

        McpTool t1 = new McpTool();
        t1.setId("tool-db-query");
        t1.setServerName("enterprise-database");
        t1.setFunctionName("query_enterprise_db");
        t1.setDisplayName("企业数据库只读查询");
        t1.setDescription("受控只读 SQL 查询：执行只读查询，遇到修改操作自动拦截");
        t1.setCategory("SYSTEM");
        t1.setEnabled(true);
        t1.setParametersSchema("{\n  \"sql\": \"SELECT id, title, created_at FROM chat_session\"\n}");
        toolRepository.save(t1);

        McpTool t2 = new McpTool();
        t2.setId("tool-calc");
        t2.setServerName("math-engine");
        t2.setFunctionName("calculate_expression");
        t2.setDisplayName("高精度数学计算");
        t2.setDescription("精确计算数学与财务公式");
        t2.setCategory("SYSTEM");
        t2.setEnabled(true);
        t2.setParametersSchema("{\n  \"expression\": \"(18500 * 0.85) + 3200\"\n}");
        toolRepository.save(t2);

        McpTool t3 = new McpTool();
        t3.setId("tool-iban");
        t3.setServerName("finance-validator");
        t3.setFunctionName("validate_iso20022_iban");
        t3.setDisplayName("银行账户格式核验");
        t3.setDescription("校验国际银行账号代码合法性");
        t3.setCategory("BUSINESS");
        t3.setEnabled(true);
        t3.setParametersSchema("{\n  \"iban\": \"DE89370400440532013000\"\n}");
        toolRepository.save(t3);

        McpTool t4 = new McpTool();
        t4.setId("tool-tax-credit");
        t4.setServerName("secops-validator");
        t4.setFunctionName("verify_tax_credit_code");
        t4.setDisplayName("企业统一社会信用代码核验");
        t4.setDescription("校验统一社会信用代码格式规范");
        t4.setCategory("BUSINESS");
        t4.setEnabled(true);
        t4.setParametersSchema("{\n  \"code\": \"91310000775785552L\"\n}");
        toolRepository.save(t4);
    }

    private void initDefaultWorkflowsIfEmpty() {
        if (workflowRepository.count() > 0) return;

        WorkflowDefinition wf1 = new WorkflowDefinition();
        wf1.setId("wf-fin-reimbursement");
        wf1.setName("员工差旅报销初审与限额合规核查");
        wf1.setCategory("财务合规");
        wf1.setBadge("合规风控");
        wf1.setIcon("Briefcase");
        wf1.setDescription("自动解析报销凭证与明细 → 对标公司差旅制度限额 → 超标金额自动拦截并生成审批工单 → 审计存证入库");
        wf1.setTags("财务制度, 费用初审, 限额核算, 审批工单");
        wf1.setPromptTemplate("请审核此笔差旅报销申请：核验住宿与交通明细，依据公司差旅手册比对住宿限额标准，若存在超标情况自动提醒并生成复核工单。");
        wf1.setVersion("v2.0.0");
        wf1.setEnabled(true);
        wf1.setTopologyJson("[{\"id\":\"step-fin-extract\",\"name\":\"1. 报销凭证参数提取与脱敏\",\"type\":\"INPUT_EXTRACT\",\"config\":{\"fields\":\"applicant, amount, city, hotel_fee\",\"maskSensitive\":true}},{\"id\":\"step-fin-rule\",\"name\":\"2. 差旅合规标准规则校验\",\"type\":\"RULE_CHECK\",\"config\":{\"ruleExpr\":\"amount <= 600\",\"boundDoc\":\"企业员工差旅报销与合规手册\"}},{\"id\":\"step-fin-hitl\",\"name\":\"3. 财务主管人工复核闸口\",\"type\":\"HITL_APPROVAL\",\"config\":{\"approverRole\":\"财务主管\",\"timeoutSeconds\":3600}},{\"id\":\"step-fin-api\",\"name\":\"4. 财务核销系统接口同步\",\"type\":\"HTTP_TOOL\",\"config\":{\"apiUrl\":\"http://localhost:8080/api/health\",\"apiMethod\":\"GET\"}},{\"id\":\"step-fin-audit\",\"name\":\"5. 全流程防篡改审计存证\",\"type\":\"LLM_REDUCE\",\"config\":{\"outputFormat\":\"STRUCTURED_AUDIT\"}}]");
        workflowRepository.save(wf1);

        WorkflowDefinition wf2 = new WorkflowDefinition();
        wf2.setId("wf-sre-patrol");
        wf2.setName("生产系统安全巡检与高危变更核准");
        wf2.setCategory("系统运维");
        wf2.setBadge("生产安全");
        wf2.setIcon("ShieldCheck");
        wf2.setDescription("解析运维指令与目标资源 → 校验物理只读权限白名单 → 检测 DROP/DELETE 危险变更并强行阻断 → 调度工单审批");
        wf2.setTags("安全规范, 受控执行, 高危阻断, 物理隔离");
        wf2.setPromptTemplate("请检查生产数据库状态：首先核对数据库安全规范，然后查询当前会话表状态；若包含删除或修改等危险操作，自动暂停并提交管理员人工审批。");
        wf2.setVersion("v2.1.0");
        wf2.setEnabled(true);
        wf2.setTopologyJson("[{\"id\":\"step-sre-extract\",\"name\":\"1. 运维指令解析与意图识别\",\"type\":\"INPUT_EXTRACT\",\"config\":{\"fields\":\"applicant, targetDb, action\",\"maskSensitive\":true}},{\"id\":\"step-sre-rule\",\"name\":\"2. 变更风险等级与规则裁决\",\"type\":\"RULE_CHECK\",\"config\":{\"ruleExpr\":\"action != 'DROP_TABLE' && amount <= 50000\"}},{\"id\":\"step-sre-hitl\",\"name\":\"3. 技术总监高危操作授权闸口\",\"type\":\"HITL_APPROVAL\",\"config\":{\"approverRole\":\"技术总监\",\"timeoutSeconds\":1800}},{\"id\":\"step-sre-api\",\"name\":\"4. 数据库安全网关受控下发\",\"type\":\"HTTP_TOOL\",\"config\":{\"apiUrl\":\"http://localhost:8080/api/health\",\"apiMethod\":\"GET\"}},{\"id\":\"step-sre-audit\",\"name\":\"5. SRE 运维操作日志防篡改存证\",\"type\":\"LLM_REDUCE\",\"config\":{\"outputFormat\":\"STRUCTURED_AUDIT\"}}]");
        workflowRepository.save(wf2);

        WorkflowDefinition wf3 = new WorkflowDefinition();
        wf3.setId("wf-legal-contract");
        wf3.setName("商业采购合同穿透合规与付款初审");
        wf3.setCategory("合同法务");
        wf3.setBadge("法务合规");
        wf3.setIcon("FileText");
        wf3.setDescription("合同核心条款要素提取 → 比对法务库知识库标准与账期限制 → 大额预付条款高危预警 → 归档存证");
        wf3.setTags("合同审查, 账期核对, 预付款风控, 法务留痕");
        wf3.setPromptTemplate("全面审查商业采购合同与付款事项：核对合同金额、预付款比例与账期，对大额支出或条款不符事项进行提示，人工确认后生成归档报告。");
        wf3.setVersion("v2.0.0");
        wf3.setEnabled(true);
        wf3.setTopologyJson("[{\"id\":\"step-legal-extract\",\"name\":\"1. 合同文本关键要素抽取\",\"type\":\"INPUT_EXTRACT\",\"config\":{\"fields\":\"partner, totalAmount, prepaymentRatio\",\"maskSensitive\":true}},{\"id\":\"step-legal-rule\",\"name\":\"2. 法务合规规则与账期校验\",\"type\":\"RULE_CHECK\",\"config\":{\"ruleExpr\":\"prepaymentRatio <= 0.3 && totalAmount <= 200000\"}},{\"id\":\"step-legal-hitl\",\"name\":\"3. 法务负责人合规复核签章\",\"type\":\"HITL_APPROVAL\",\"config\":{\"approverRole\":\"法务主管\",\"timeoutSeconds\":7200}},{\"id\":\"step-legal-api\",\"name\":\"4. ERP 采购系统数据校验\",\"type\":\"HTTP_TOOL\",\"config\":{\"apiUrl\":\"http://localhost:8080/api/health\",\"apiMethod\":\"GET\"}},{\"id\":\"step-legal-audit\",\"name\":\"5. 合同合规审查存证归档\",\"type\":\"LLM_REDUCE\",\"config\":{\"outputFormat\":\"STRUCTURED_AUDIT\"}}]");
        workflowRepository.save(wf3);
    }

    private void initDefaultKnowledgeIfEmpty() {
        if (knowledgeRepository.count() > 0) return;

        KnowledgeChunk k1 = new KnowledgeChunk();
        k1.setId("chunk-default-fin");
        k1.setDocTitle("企业员工差旅报销与合规手册");
        k1.setChunkIndex(0);
        k1.setCategory("FINANCE");
        k1.setKeywords("差旅报销, 一线城市, 住宿标准, 超标提醒, 财务审核");
        k1.setContent("# 企业员工差旅报销制度\n\n## 1. 住宿标准与报销限额\n- 一线城市（北京、上海、广州、深圳）普通员工住宿报销标准上限为 600 元/间夜。\n- 新一线城市及省会城市住宿报销标准上限为 450 元/间夜。\n- 其余城市住宿报销标准上限为 300 元/间夜。\n\n## 2. 交通工具出行规范\n- 跨城交通优先选择高铁二等座或经济舱机票。\n- 严禁未经前置特批私自乘坐商务座或头等舱。\n\n## 3. 超标拦截与审批流程\n- 超标差额部分系统自动拦截预警，需发起二级财务特批流程。\n- 特批未获批准的，超标部分由员工个人自理。");
        knowledgeRepository.save(k1);

        KnowledgeChunk k2 = new KnowledgeChunk();
        k2.setId("chunk-default-sre");
        k2.setDocTitle("生产系统安全运维规约");
        k2.setChunkIndex(0);
        k2.setCategory("SRE_OPS");
        k2.setKeywords("DROP, TRUNCATE, DELETE, 人工审批, 数据安全");
        k2.setContent("# 生产系统安全运维规约\n\n## 1. 生产数据操作红线\n- 严禁未经管理员审批在生产环境执行无 WHERE 条件的 DELETE 或 UPDATE 变更。\n- 单次批量删除或修改超过 1000 行记录，必须提前备份并提交审批。\n\n## 2. 高危 DDL 操作审批\n- 凡涉及 DROP TABLE、TRUNCATE TABLE、ALTER TABLE 加唯一索引等重度操作，必须通过管理员人工审批放行。\n- 变更必须在业务低峰期（凌晨 00:00 - 06:00）窗口内执行。\n\n## 3. 查询操作受控\n- 数据库查询工具仅限执行受控 SELECT 查询与 EXPLAIN 分析。\n- 单次查询结果上限不得超过 1000 条，禁止 SELECT * 跨大表扫描。");
        knowledgeRepository.save(k2);

        KnowledgeChunk k3 = new KnowledgeChunk();
        k3.setId("chunk-default-legal");
        k3.setDocTitle("企业商务合同与付款风控规范");
        k3.setChunkIndex(0);
        k3.setCategory("LEGAL");
        k3.setKeywords("合同审核, 单笔付款, 合规审查, 法务审批");
        k3.setContent("# 企业合同与付款风控规约\n\n## 1. 付款金额分级风控\n- 单笔金额超过人民币 50,000 元的支出类合同，严禁直接自动支付，必须附带法务初审意见与复核凭据。\n- 超过人民币 200,000 元的重大采购，必须由财务总监与业务主管双人联合审批。\n\n## 2. 预付款与违约金条款\n- 凡涉及预付款比例超过总标的 30% 的协议，系统必须提醒合规预警并提交法务特批。\n- 合同履约保证金比例不应低于 10%，账期要求不少于 30 个工作日。\n\n## 3. 印章与签署安全\n- 涉及加盖公章与法人章的授权指令，严禁在未完成审批的情况下执行。");
        knowledgeRepository.save(k3);
    }
}

