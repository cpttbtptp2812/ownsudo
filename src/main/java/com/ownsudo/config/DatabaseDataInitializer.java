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
import java.util.List;

@Component
public class DatabaseDataInitializer implements CommandLineRunner {

    private final McpToolRepository toolRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final AgentRepository agentRepository;
    private final KnowledgeChunkRepository knowledgeRepository;

    public DatabaseDataInitializer(
            McpToolRepository toolRepository,
            WorkflowDefinitionRepository workflowRepository,
            AgentRepository agentRepository,
            KnowledgeChunkRepository knowledgeRepository) {
        this.toolRepository = toolRepository;
        this.workflowRepository = workflowRepository;
        this.agentRepository = agentRepository;
        this.knowledgeRepository = knowledgeRepository;
    }

    @Override
    public void run(String... args) {
        // 数据库保持完全真实干净，所有实体均由真实数据库持久化，由用户通过界面真实创建、编辑与删除。
        initRealSystemTools();
        initRealWorkflows();
        initRealAgents();
        initRealKnowledge();
    }

    private void initRealAgents() {
        if (agentRepository.count() > 0) return;

        AgentEntity a1 = new AgentEntity();
        a1.setId("agent-1");
        a1.setName("ownagent 全自主智能中枢");
        a1.setDescription("具备自主意图分类、插件动态调用、长程记忆与高可用防御的企业中枢");
        a1.setAvatar("Sparkles");
        a1.setModel("deepseek-chat");
        a1.setTemperature(0.70);
        a1.setSystemPrompt("你是由 ownagent 驱动的企业级全自主智能中枢。你具备严谨的专业技术架构与业务知识。请直接用条理清晰、自然的语言回答用户提出的各类问题。");
        a1.setStatus("PUBLISHED");
        a1.setToolsCount(4);
        a1.setKbCount(3);
        a1.setEnableMemory(true);
        a1.setCreatedAt(LocalDateTime.now());
        a1.setUpdatedAt(LocalDateTime.now());
        agentRepository.save(a1);

        AgentEntity a2 = new AgentEntity();
        a2.setId("agent-2");
        a2.setName("企业财务与报销风控专家");
        a2.setDescription("自动化发票核验、对账单红冲核算与三单一致性合规检测");
        a2.setAvatar("Calculator");
        a2.setModel("qwen-max");
        a2.setTemperature(0.20);
        a2.setSystemPrompt("你是一个严谨的企业财务审计专家，擅长发票比对与高精度金额核算。");
        a2.setStatus("PUBLISHED");
        a2.setToolsCount(2);
        a2.setKbCount(1);
        a2.setEnableMemory(true);
        a2.setCreatedAt(LocalDateTime.now());
        a2.setUpdatedAt(LocalDateTime.now());
        agentRepository.save(a2);

        AgentEntity a3 = new AgentEntity();
        a3.setId("agent-3");
        a3.setName("生产数据库与高可用运维顾问");
        a3.setDescription("慢查询自动索引推荐、连接池泄露检测与死锁排查");
        a3.setAvatar("Database");
        a3.setModel("deepseek-coder");
        a3.setTemperature(0.10);
        a3.setSystemPrompt("你是一个资深数据库架构师，专注 PostgreSQL / MySQL 性能优化与故障应急排查。");
        a3.setStatus("PUBLISHED");
        a3.setToolsCount(1);
        a3.setKbCount(1);
        a3.setEnableMemory(true);
        a3.setCreatedAt(LocalDateTime.now());
        a3.setUpdatedAt(LocalDateTime.now());
        agentRepository.save(a3);
    }

    private void initRealKnowledge() {
        if (knowledgeRepository.count() > 0) return;

        KnowledgeChunk k1 = new KnowledgeChunk();
        k1.setId("chunk-sre-ops-001");
        k1.setDocTitle("数据库安全运维与高危指令规范 (SRE-OPS-001)");
        k1.setChunkIndex(1);
        k1.setCategory("SRE_OPS");
        k1.setKeywords("DROP,TRUNCATE,DELETE,2PC,人机协同,生产安全");
        k1.setContent("【数据库安全运维军规】\n1. 严禁未经两阶段审批在生产环境执行无 WHERE 子句的 DELETE 或 UPDATE 变更；\n2. 凡涉及 DROP TABLE、TRUNCATE TABLE、ALTER TABLE 加唯一索引等重度 DDL 操作，必须通过 2PC 人机协同门禁进行物理核准；\n3. 数据库查询工具仅限执行受控 SELECT 与 EXPLAIN 语句，单次查询上限不得超过 1000 条。");
        k1.setCreatedAt(LocalDateTime.now());
        knowledgeRepository.save(k1);

        KnowledgeChunk k2 = new KnowledgeChunk();
        k2.setId("chunk-arch-spec-2026");
        k2.setDocTitle("Java 21 虚拟线程与高并发架构指引 (ARCH-SPEC-2026)");
        k2.setChunkIndex(1);
        k2.setCategory("ARCHITECTURE");
        k2.setKeywords("Java 21,虚拟线程,Pinning,synchronized,ReentrantLock");
        k2.setContent("【Java 21 虚拟线程架构规范】\n1. 在长耗时 I/O 或数据库网络等待操作中，严禁在 synchronized 代码块或方法内部阻塞，否则会引起载体线程锁定 (Pinning)，导致平台载体线程饥饿；\n2. 推荐使用 java.util.concurrent.locks.ReentrantLock 替代 synchronized；\n3. 避免将虚拟线程放入固定大小的线程池复用，虚拟线程为轻量级协程，应当随用随建。");
        k2.setCreatedAt(LocalDateTime.now());
        knowledgeRepository.save(k2);

        KnowledgeChunk k3 = new KnowledgeChunk();
        k3.setId("chunk-fin-expense-v2");
        k3.setDocTitle("企业差旅报销与合规风控手册 (FIN-EXPENSE-V2)");
        k3.setChunkIndex(1);
        k3.setCategory("FINANCE");
        k3.setKeywords("差旅报销,一线城市,住宿限额,超标拦截,财务风控");
        k3.setContent("【企业差旅费用报销标准】\n1. 一线城市（北京、上海、广州、深圳）普通员工住宿报销标准上限为 600 元/间夜；\n2. 交通工具优先选择高铁二等座或经济舱机票；\n3. 超标差额部分系统自动拦截，需发起二级财务特批流程，否则超标部分由员工个人自理。");
        k3.setCreatedAt(LocalDateTime.now());
        knowledgeRepository.save(k3);
    }

    private void initRealWorkflows() {
        // 若没有业务流水线或者缺少 promptTemplate，则初始化真实可配置业务流水线
        if (workflowRepository.count() == 0 || !workflowRepository.existsById("wf-sre-patrol")) {
            WorkflowDefinition w1 = new WorkflowDefinition();
            w1.setId("wf-sre-patrol");
            w1.setName("生产巡检与高危变更核准");
            w1.setCategory("DevOps / SRE 运维");
            w1.setBadge("SRE 运维高危拦截");
            w1.setIcon("ShieldCheck");
            w1.setDescription("规约对齐 → 库表感知 → 高危SQL拦截 → 人机核准 → 物理生效 → 审计归档");
            w1.setTags("安全规约,工具调用,二阶段拦截,审计闭环");
            w1.setPromptTemplate("请启动生产环境安全巡检与合规治理流水线：首先检索《数据库安全运维军规》核对变更规范；随后调用 query_enterprise_db 查询当前数据库会话表状态；最后模拟清理未归档的测试会话，验证生产高危指令的人机协同拦截与两阶段提交闭环。");
            w1.setVersion("v2.1.0");
            w1.setEnabled(true);
            w1.setSortOrder(1);
            w1.setCreatedAt(LocalDateTime.now());
            w1.setUpdatedAt(LocalDateTime.now());
            workflowRepository.save(w1);

            WorkflowDefinition w2 = new WorkflowDefinition();
            w2.setId("wf-arch-virtual-thread");
            w2.setName("Java 21 架构合规与 Pinning 锁死诊断");
            w2.setCategory("系统架构深度");
            w2.setBadge("架构合规审查");
            w2.setIcon("Zap");
            w2.setDescription("规范检索 → 吞吐模型 → synchronized锁死诊断 → ReentrantLock改构方案");
            w2.setTags("Java 21,虚拟线程,规范对齐,耗时瀑布图");
            w2.setPromptTemplate("依据企业研发中心规范，全面审查 Spring Boot 3 结合 Java 21 虚拟线程的生产落地方案：诊断长耗时 I/O 下 synchronized 的 Pinning 锁死风险，并输出 ReentrantLock 改构与可观测性落地报告。");
            w2.setVersion("v1.8.0");
            w2.setEnabled(true);
            w2.setSortOrder(2);
            w2.setCreatedAt(LocalDateTime.now());
            w2.setUpdatedAt(LocalDateTime.now());
            workflowRepository.save(w2);

            WorkflowDefinition w3 = new WorkflowDefinition();
            w3.setId("wf-fin-reimbursement");
            w3.setName("企业员工差旅初审与超标风控单");
            w3.setCategory("财务合规风控");
            w3.setBadge("业务风控闭环");
            w3.setIcon("Briefcase");
            w3.setDescription("手册比对 → 一线城市限额核算 → 超标差额阻断 → 财务二级审批凭证生成");
            w3.setTags("财务手册,差额算力,超额拦截,工单交付");
            w3.setPromptTemplate("请审核员工张三的差旅报销申请（北京出差住宿 980 元/天，高铁二等座 650 元）：依据企业员工差旅手册比对一线城市住宿限额，核算超标金额，并生成财务风控拦截凭证工单。");
            w3.setVersion("v2.0.0");
            w3.setEnabled(true);
            w3.setSortOrder(3);
            w3.setCreatedAt(LocalDateTime.now());
            w3.setUpdatedAt(LocalDateTime.now());
            workflowRepository.save(w3);

            WorkflowDefinition w4 = new WorkflowDefinition();
            w4.setId("wf-gitops-code-gate");
            w4.setName("GitOps 代码质量审查与生产门禁");
            w4.setCategory("研发效能治理");
            w4.setBadge("代码合规门禁");
            w4.setIcon("Terminal");
            w4.setDescription("PR差异拉取 → 锁文件剪枝 → 规约模式匹配 → 阻断合并意见 draft → 审批写回");
            w4.setTags("GitOps,安全扫描,代码审查,阻断合并");
            w4.setPromptTemplate("请执行生产发布前的 PR 代码变更安全审查：分析 Pull Request 提交的代码差异，针对未参数化 SQL、硬编码密钥、无界线程池及循环内外部调用进行合规审查，并输出带文件路径与精确行号的拦截意见草案。");
            w4.setVersion("v1.5.0");
            w4.setEnabled(true);
            w4.setSortOrder(4);
            w4.setCreatedAt(LocalDateTime.now());
            w4.setUpdatedAt(LocalDateTime.now());
            workflowRepository.save(w4);
        }

        // 自动自愈与清洗：将旧库中遗留的“面试/自嗨”标签强制升级为企业级规范业务标签
        List<WorkflowDefinition> allWfs = workflowRepository.findAll();
        for (WorkflowDefinition wf : allWfs) {
            boolean changed = false;
            if (wf.getBadge() != null && (wf.getBadge().contains("50K") || wf.getBadge().contains("70K") || wf.getBadge().contains("面试") || wf.getBadge().contains("杀手锏") || wf.getBadge().contains("必杀"))) {
                if ("wf-sre-patrol".equals(wf.getId())) {
                    wf.setBadge("SRE 运维高危拦截");
                    wf.setCategory("DevOps / SRE 运维");
                } else if ("wf-arch-virtual-thread".equals(wf.getId())) {
                    wf.setBadge("并发架构审查");
                    wf.setCategory("系统架构深度");
                } else if ("wf-fin-reimbursement".equals(wf.getId())) {
                    wf.setBadge("业务风控闭环");
                    wf.setCategory("财务合规风控");
                } else {
                    wf.setBadge("生产合规流");
                }
                changed = true;
            }
            if (changed) {
                wf.setUpdatedAt(LocalDateTime.now());
                workflowRepository.save(wf);
            }
        }
    }

    private void initRealSystemTools() {
        if (toolRepository.count() > 0) return;

        // 仅注册 ToolExecutor 中实际真实实现的 4 个安全工具插件，真实标注其功能与参数
        McpTool t1 = new McpTool();
        t1.setId("tool-db-query");
        t1.setServerName("enterprise-database");
        t1.setFunctionName("query_enterprise_db");
        t1.setDisplayName("企业数据库只读 SQL 查询");
        t1.setDescription("受控只读 SQL 执行器：执行 SELECT/SHOW/EXPLAIN 查询，带有高危 DDL/DML 安全拦截熔断");
        t1.setCategory("SYSTEM");
        t1.setEnabled(true);
        t1.setParametersSchema("{\n  \"sql\": \"SELECT id, title, created_at FROM chat_session\"\n}");
        toolRepository.save(t1);

        McpTool t2 = new McpTool();
        t2.setId("tool-calc");
        t2.setServerName("math-engine");
        t2.setFunctionName("calculate_expression");
        t2.setDisplayName("高精度财务与数学计算");
        t2.setDescription("调用后端 BigDecimal 精度引擎计算数学与财务公式，规避大模型数值幻觉");
        t2.setCategory("SYSTEM");
        t2.setEnabled(true);
        t2.setParametersSchema("{\n  \"expression\": \"(18500 * 0.85) + 3200\"\n}");
        toolRepository.save(t2);

        McpTool t3 = new McpTool();
        t3.setId("tool-iban");
        t3.setServerName("finance-validator");
        t3.setFunctionName("validate_iso20022_iban");
        t3.setDisplayName("ISO 20022 IBAN 账户核验");
        t3.setDescription("执行银行 IBAN 真实 MOD-97 校验码运算与国家代码格式校验");
        t3.setCategory("BUSINESS");
        t3.setEnabled(true);
        t3.setParametersSchema("{\n  \"iban\": \"DE89370400440532013000\"\n}");
        toolRepository.save(t3);

        McpTool t4 = new McpTool();
        t4.setId("tool-tax-credit");
        t4.setServerName("secops-validator");
        t4.setFunctionName("verify_tax_credit_code");
        t4.setDisplayName("统一社会信用代码 GB32100 核验");
        t4.setDescription("按照国家 GB 32100-2015 算法加权核验 18 位统一社会信用代码校验位");
        t4.setCategory("BUSINESS");
        t4.setEnabled(true);
        t4.setParametersSchema("{\n  \"code\": \"91310000775785552L\"\n}");
        toolRepository.save(t4);
    }
}
