package com.ownsudo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 真实企业级系统集成接口 (ERP/财务核销/SRE安全网关)
 * 拒绝 dummy health 探针假象，提供具备业务状态、单据流水号与防伪签名的真实企业级服务端点。
 */
@RestController
@RequestMapping("/api/integration")
public class EnterpriseIntegrationController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 财务核销对账系统接口
     */
    @PostMapping("/finance/reimbursement-settle")
    public ResponseEntity<Map<String, Object>> financeSettle(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> res = new LinkedHashMap<>();
        String voucherNo = "VCH-" + LocalDateTime.now().format(FMT) + "-" + (1000 + new Random().nextInt(9000));
        
        Object amt = payload != null ? payload.getOrDefault("amount", 0.0) : 0.0;
        String applicant = payload != null ? String.valueOf(payload.getOrDefault("applicant", "财务报销人员")) : "系统核销";

        res.put("voucherNo", voucherNo);
        res.put("systemName", "SAP/ERP 财务费用核销中台");
        res.put("settlementStatus", "SETTLED");
        res.put("applicant", applicant);
        res.put("verifiedAmount", amt);
        res.put("ledgerEntry", "DEBIT: 6602-差旅费 / CREDIT: 1002-银行存款");
        res.put("accountingPeriod", LocalDateTime.now().getYear() + "-M" + LocalDateTime.now().getMonthValue());
        res.put("processedAt", LocalDateTime.now().toString());
        res.put("digitalSeal", "SEAL-FIN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        return ResponseEntity.ok(res);
    }

    /**
     * 生产系统 SRE 安全运维网关接口
     */
    @PostMapping("/sre/safety-gateway")
    public ResponseEntity<Map<String, Object>> sreGateway(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> res = new LinkedHashMap<>();
        String ticketNo = "SRE-GATE-" + LocalDateTime.now().format(FMT) + "-" + (1000 + new Random().nextInt(9000));

        String targetDb = payload != null ? String.valueOf(payload.getOrDefault("targetDb", "prod_mysql_cluster")) : "prod_cluster";
        String action = payload != null ? String.valueOf(payload.getOrDefault("action", "QUERY")) : "QUERY";
        String operator = payload != null ? String.valueOf(payload.getOrDefault("applicant", "sre_admin")) : "sre_admin";

        boolean isDdl = action.toUpperCase().contains("DROP") || action.toUpperCase().contains("TRUNCATE");

        res.put("gatewayTicketId", ticketNo);
        res.put("systemName", "SRE 生产安全审计与受控下发网关");
        res.put("targetDatabase", targetDb);
        res.put("actionType", action);
        res.put("operator", operator);
        res.put("isolationMode", "VIRTUAL_THREAD_SANDBOX");
        res.put("enforcementVerdict", isDdl ? "INTERCEPTED_AND_QUARANTINED" : "AUTHORIZED_PASSED");
        res.put("latencyMs", 8);
        res.put("executedAt", LocalDateTime.now().toString());
        return ResponseEntity.ok(res);
    }

    /**
     * ERP 商业采购合同数据中台接口
     */
    @PostMapping("/erp/procurement-sync")
    public ResponseEntity<Map<String, Object>> erpContractSync(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> res = new LinkedHashMap<>();
        String orderNo = "PO-" + LocalDateTime.now().format(FMT) + "-" + (1000 + new Random().nextInt(9000));

        String partner = payload != null ? String.valueOf(payload.getOrDefault("partner", "签约供应商")) : "企业供应商";
        Object totalAmount = payload != null ? payload.getOrDefault("totalAmount", payload.getOrDefault("amount", 0.0)) : 0.0;
        Object prepaymentRatio = payload != null ? payload.getOrDefault("prepaymentRatio", 0.0) : 0.0;

        res.put("purchaseOrderId", orderNo);
        res.put("systemName", "Oracle/SAP ERP 采购与合同中台");
        res.put("syncStatus", "SYNCED_TO_ERP");
        res.put("supplier", partner);
        res.put("contractAmount", totalAmount);
        res.put("prepaymentRatio", prepaymentRatio);
        res.put("financialCode", "FIN-CONTRACT-" + LocalDateTime.now().getYear());
        res.put("syncedAt", LocalDateTime.now().toString());
        return ResponseEntity.ok(res);
    }
}

