package com.ownsudo.config;

import com.ownsudo.entity.McpTool;
import com.ownsudo.repository.AgentRepository;
import com.ownsudo.repository.KnowledgeChunkRepository;
import com.ownsudo.repository.McpToolRepository;
import com.ownsudo.repository.WorkflowDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 数据库启动初始化类
 * 
 * 严格遵循生产级零 Demo、纯净空租户原则：
 * 1. 绝不自动向空库灌入虚假 Demo 智能体、流程或演示知识库；
 * 2. 纯净部署时，智能体表、工作流表、知识库表保持纯净空状态，交由客户或管理员通过界面 CRUD 自行创建，或由 SQL 脚本按需导入；
 * 3. 仅注册核心底层系统级受控工具（SQL查询、数学公式、IBAN校验、统一信用代码核验），确保系统具备基础运行时调度能力。
 */
@Component
public class DatabaseDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDataInitializer.class);

    private final McpToolRepository toolRepository;

    public DatabaseDataInitializer(McpToolRepository toolRepository) {
        this.toolRepository = toolRepository;
    }

    @Override
    public void run(String... args) {
        initSystemToolsIfEmpty();
        log.info("【系统启动】生产级纯净运行时检查完成：已确保基础系统级受控工具注册，业务表保持纯净零Demo。");
    }

    /**
     * 仅注册系统底层真实受控工具（确保运行时具备可执行函数）
     */
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
}
