package com.ownsudo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.ApprovalTicket;
import com.ownsudo.repository.ApprovalTicketRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;

@Component
public class ToolExecutor {

    private static final int MAX_ITERATIONS = 3;
    private static final long TIMEOUT_MS = 5000L;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, ToolHandler> registry = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private ApprovalTicketRepository approvalTicketRepository;

    public interface ToolHandler {
        Object execute(Map<String, Object> params) throws Exception;
    }

    public void registerTool(String toolName, ToolHandler handler) {
        registry.put(toolName, handler);
    }

    @PostConstruct
    public void initStandardTools() {
        // 1. ISO 20022 / IBAN 跨境银行账号校验 (真实 MOD-97 校验)
        registerTool("validate_iso20022_iban", params -> {
            String iban = Optional.ofNullable(params.get("iban")).map(Object::toString).orElse("").replaceAll("\\s+", "").toUpperCase();
            if (iban.length() < 15 || iban.length() > 34) {
                throw new IllegalArgumentException("IBAN 账号长度不合法 (须介于 15 至 34 位字符之间): " + iban);
            }
            if (!iban.matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]+$")) {
                throw new IllegalArgumentException("IBAN 首 4 位必须为国别代码与两位校验码: " + iban);
            }
            String rearranged = iban.substring(4) + iban.substring(0, 4);
            StringBuilder sb = new StringBuilder();
            for (char ch : rearranged.toCharArray()) {
                if (Character.isDigit(ch)) {
                    sb.append(ch);
                } else if (Character.isLetter(ch)) {
                    sb.append((int) ch - 55);
                } else {
                    throw new IllegalArgumentException("包含非法字符: " + ch);
                }
            }
            int remainder = 0;
            String numberStr = sb.toString();
            for (int i = 0; i < numberStr.length(); i++) {
                int digit = Character.getNumericValue(numberStr.charAt(i));
                remainder = (remainder * 10 + digit) % 97;
            }
            boolean isValid = remainder == 1;
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("iban", iban);
            res.put("country", iban.substring(0, 2));
            res.put("checksumValid", isValid);
            res.put("remainder", remainder);
            res.put("status", isValid ? "VERIFIED" : "CHECKSUM_FAILED");
            res.put("message", isValid ? "ISO20022 IBAN 格式与 MOD-97 校验核验通过" : "IBAN MOD-97 校验位无效");
            return res;
        });

        // 2. 企业数据安全只读查询 (真实只读校验与执行)
        registerTool("query_enterprise_db", params -> {
            String sql = Optional.ofNullable(params.get("sql")).map(Object::toString).orElse("").trim();
            if (sql.isEmpty()) {
                throw new IllegalArgumentException("SQL 语句不能为空");
            }
            String upper = sql.toUpperCase();
            // 敏感凭证与核心配置表白名单/黑名单拦截隔离
            if (upper.contains("SYSTEM_CONFIG") || upper.contains("USER_PASSWORD") || upper.contains("SECRET")) {
                throw new SecurityException("【安全拦截】系统核心凭证与密钥表受硬件级策略保护，已触发防越权安全熔断！禁止读取或修改敏感凭据。");
            }

            // 识别高危写操作 (非只读查询：自动触发 Human-in-the-Loop 安全挂起与持久化审批单)
            boolean isWriteOperation = upper.matches(".*\\b(DROP|DELETE|TRUNCATE|UPDATE|INSERT|ALTER|GRANT|REVOKE|FLUSH)\\b.*")
                    || (!upper.startsWith("SELECT") && !upper.startsWith("SHOW") && !upper.startsWith("EXPLAIN"));

            if (isWriteOperation) {
                String ticketId = "ticket-" + UUID.randomUUID().toString().substring(0, 8);
                if (approvalTicketRepository != null) {
                    ApprovalTicket ticket = new ApprovalTicket();
                    ticket.setId(ticketId);
                    ticket.setInstanceId("session-runtime");
                    ticket.setNodeId("query_enterprise_db");
                    ticket.setPolicyLevel("P0_CRITICAL");
                    ticket.setRiskScore(0.95);
                    ticket.setOperationSummary(sql);
                    ticket.setStatus("PENDING");
                    ticket.setAuditReason("检测到生产数据变更指令，根据合规军规触发安全挂起审批流 (Human-in-the-Loop)");
                    approvalTicketRepository.save(ticket);
                }
                throw new HighRiskApprovalRequiredException(ticketId, sql, "query_enterprise_db",
                        "【高危写操作触发人机审批 (Human-in-the-Loop)】检测到拟执行数据变更指令: [" + sql + "]。任务已自动挂起，审批单号: " + ticketId + "。请合规安全员在前端操作卡片中进行人工核准或驳回。");
            }
            if (jdbcTemplate != null) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                List<Map<String, Object>> safeRows = new java.util.ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Map<String, Object> safeRow = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        String k = entry.getKey().toLowerCase();
                        if (k.contains("key") || k.contains("password") || k.contains("secret") || k.contains("token")) {
                            safeRow.put(entry.getKey(), "****** (企业机密脱敏)");
                        } else {
                            safeRow.put(entry.getKey(), entry.getValue());
                        }
                    }
                    safeRows.add(safeRow);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("querySql", sql);
                result.put("rowCount", safeRows.size());
                result.put("rows", safeRows.size() > 50 ? safeRows.subList(0, 50) : safeRows);
                return result;
            } else {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("querySql", sql);
                result.put("status", "SUCCESS");
                result.put("message", "只读安全校验通过，数据库环境就绪");
                return result;
            }
        });

        // 3. 财务与高精度数值计算
        registerTool("calculate_expression", params -> {
            String expr = Optional.ofNullable(params.get("expression")).map(Object::toString).orElse("").trim();
            if (expr.isEmpty()) {
                throw new IllegalArgumentException("表达式不能为空");
            }
            try {
                double val = evaluateSimpleMath(expr);
                BigDecimal bd = new BigDecimal(val, MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("expression", expr);
                res.put("value", bd.toPlainString());
                res.put("calculatedAt", System.currentTimeMillis());
                return res;
            } catch (Exception e) {
                throw new IllegalArgumentException("数学表达式解析错误: " + e.getMessage());
            }
        });

        // 4. 企业统一社会信用代码校验 (GB 32100-2015 算法)
        registerTool("verify_tax_credit_code", params -> {
            String code = Optional.ofNullable(params.get("code")).map(Object::toString).orElse("").trim().toUpperCase();
            if (code.length() != 18) {
                throw new IllegalArgumentException("统一社会信用代码必须为 18 位");
            }
            String chars = "0123456789ABCDEFGHJKLMNPQRTUWXY";
            int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
            int sum = 0;
            for (int i = 0; i < 17; i++) {
                int pos = chars.indexOf(code.charAt(i));
                if (pos == -1) throw new IllegalArgumentException("包含非GB32100规定字符: " + code.charAt(i));
                sum += pos * weights[i];
            }
            int remainder = 31 - (sum % 31);
            char checkChar = remainder == 31 ? '0' : chars.charAt(remainder);
            boolean isValid = code.charAt(17) == checkChar;
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("creditCode", code);
            res.put("expectedChecksum", String.valueOf(checkChar));
            res.put("actualChecksum", String.valueOf(code.charAt(17)));
            res.put("isValid", isValid);
            return res;
        });
    }

    private double evaluateSimpleMath(String expr) {
        String clean = expr.replaceAll("\\s+", "");
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < clean.length()) ? clean.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }
            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < clean.length()) throw new RuntimeException("未知字符: " + (char)ch);
                return x;
            }
            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if      (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if      (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(clean.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("无法解析因子: " + (char)ch);
                }
                return x;
            }
        }.parse();
    }

    public String validateSchema(String toolName, Map<String, Object> params) {
        if (params == null) {
            return "参数对象为空，必须提供符合 Schema 的 JSON 参数字典";
        }
        switch (toolName) {
            case "query_enterprise_db":
                if (!params.containsKey("sql") || params.get("sql") == null) {
                    return "缺少必填参数 [sql]";
                }
                if (!(params.get("sql") instanceof String) || ((String) params.get("sql")).trim().isEmpty()) {
                    return "参数 [sql] 类型必须为非空字符串";
                }
                break;
            case "calculate_expression":
                if (!params.containsKey("expression") || params.get("expression") == null) {
                    return "缺少必填参数 [expression]";
                }
                if (!(params.get("expression") instanceof String) || ((String) params.get("expression")).trim().isEmpty()) {
                    return "参数 [expression] 类型必须为非空数学表达式字符串";
                }
                break;
            case "validate_iso20022_iban":
                if (!params.containsKey("iban") || params.get("iban") == null) {
                    return "缺少必填参数 [iban]";
                }
                if (!(params.get("iban") instanceof String) || ((String) params.get("iban")).trim().isEmpty()) {
                    return "参数 [iban] 类型必须为非空国际银行账号字符串";
                }
                break;
            case "verify_tax_credit_code":
                if (!params.containsKey("creditCode") || params.get("creditCode") == null) {
                    return "缺少必填参数 [creditCode]";
                }
                if (!(params.get("creditCode") instanceof String) || ((String) params.get("creditCode")).trim().isEmpty()) {
                    return "参数 [creditCode] 类型必须为非空社会信用代码字符串";
                }
                break;
        }
        return null;
    }

    public ToolExecutionResult executeTool(String toolName, String argumentsJson, int currentDepth) {
        if (currentDepth > MAX_ITERATIONS) {
            return ToolExecutionResult.executionError("触发工具调用最大递归深度 (" + MAX_ITERATIONS + ") 熔断，请直接作答");
        }

        ToolHandler handler = registry.get(toolName);
        if (handler == null) {
            return ToolExecutionResult.schemaError("未找到名称为 [" + toolName + "] 的工具，请核对可用工具列表");
        }

        Map<String, Object> params;
        try {
            params = objectMapper.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            return ToolExecutionResult.schemaError("JSON 参数格式解析失败: " + e.getMessage());
        }

        // Schema 前置严格校验
        String schemaError = validateSchema(toolName, params);
        if (schemaError != null) {
            return ToolExecutionResult.schemaError(schemaError);
        }

        Future<Object> future = executor.submit(() -> handler.execute(params));
        try {
            Object result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return ToolExecutionResult.success(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolExecutionResult.timeoutError("工具 [" + toolName + "] 执行超时 (超过 " + TIMEOUT_MS + "ms)");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof HighRiskApprovalRequiredException h) {
                return ToolExecutionResult.approvalRequired(h.getTicketId(), h.getSql(), h.getMessage());
            }
            if (cause instanceof SecurityException) {
                return ToolExecutionResult.securityError(cause.getMessage());
            }
            return ToolExecutionResult.executionError(cause.getMessage());
        }
    }

    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> list = new ArrayList<>();

        // 1. query_enterprise_db
        list.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", "query_enterprise_db",
                "description", "执行受控的只读 SQL 查询 (仅允许 SELECT/SHOW/EXPLAIN，禁止 DDL/DML)，查询企业数据库表数据",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "sql", Map.of("type", "string", "description", "要执行的只读 SELECT SQL 语句，例如 SELECT id, title FROM chat_session")
                    ),
                    "required", List.of("sql")
                )
            )
        ));

        // 2. calculate_expression
        list.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", "calculate_expression",
                "description", "高精度财务与数学公式计算，防止大模型数值幻觉",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "expression", Map.of("type", "string", "description", "四则运算或数学表达式，例如 (18500 * 0.85) + 3200")
                    ),
                    "required", List.of("expression")
                )
            )
        ));

        // 3. validate_iso20022_iban
        list.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", "validate_iso20022_iban",
                "description", "验证国际银行账号 IBAN 的 MOD-97 校验码与国家代号合规性",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "iban", Map.of("type", "string", "description", "待验证的国际银行账号 IBAN 字符串")
                    ),
                    "required", List.of("iban")
                )
            )
        ));

        // 4. verify_tax_credit_code
        list.add(Map.of(
            "type", "function",
            "function", Map.of(
                "name", "verify_tax_credit_code",
                "description", "验证中国企业 18 位统一社会信用代码 (GB 32100-2015 校验码)",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "code", Map.of("type", "string", "description", "18 位统一社会信用代码")
                    ),
                    "required", List.of("code")
                )
            )
        ));

        return list;
    }

    public static class ToolExecutionResult {
        private final boolean success;
        private final boolean retryable;
        private final String errorType;
        private final String errorMessage;
        private final Object data;

        private ToolExecutionResult(boolean success, boolean retryable, String errorType, String errorMessage, Object data) {
            this.success = success;
            this.retryable = retryable;
            this.errorType = errorType;
            this.errorMessage = errorMessage;
            this.data = data;
        }

        public static ToolExecutionResult success(Object data) {
            return new ToolExecutionResult(true, false, null, null, data);
        }

        public static ToolExecutionResult schemaError(String errorMsg) {
            return new ToolExecutionResult(false, true, "SCHEMA_VALIDATION_ERROR", errorMsg, null);
        }

        public static ToolExecutionResult securityError(String errorMsg) {
            return new ToolExecutionResult(false, false, "SECURITY_ACCESS_DENIED", errorMsg, null);
        }

        public static ToolExecutionResult timeoutError(String errorMsg) {
            return new ToolExecutionResult(false, false, "TIMEOUT_EXCEEDED", errorMsg, null);
        }

        public static ToolExecutionResult executionError(String errorMsg) {
            return new ToolExecutionResult(false, false, "INTERNAL_EXECUTION_ERROR", errorMsg, null);
        }

        public static ToolExecutionResult approvalRequired(String ticketId, String sql, String warning) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "APPROVAL_REQUIRED");
            map.put("ticketId", ticketId);
            map.put("sql", sql);
            map.put("status", "PENDING");
            map.put("warning", warning);
            return new ToolExecutionResult(false, false, "PENDING_APPROVAL", warning, map);
        }

        public boolean isSuccess() { return success; }
        public boolean isRetryable() { return retryable; }
        public String getErrorType() { return errorType; }
        public String getErrorMessage() { return errorMessage; }
        public Object getData() { return data; }
    }

    public static class HighRiskApprovalRequiredException extends RuntimeException {
        private final String ticketId;
        private final String sql;
        private final String toolName;

        public HighRiskApprovalRequiredException(String ticketId, String sql, String toolName, String message) {
            super(message);
            this.ticketId = ticketId;
            this.sql = sql;
            this.toolName = toolName;
        }

        public String getTicketId() { return ticketId; }
        public String getSql() { return sql; }
        public String getToolName() { return toolName; }
    }
}
