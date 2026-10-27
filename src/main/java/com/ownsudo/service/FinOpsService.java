package com.ownsudo.service;

import com.ownsudo.repository.NodeExecutionRepository;
import com.ownsudo.repository.WorkflowInstanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class FinOpsService {

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private NodeExecutionRepository nodeExecutionRepository;

    public Map<String, Object> getFinOpsMetrics() {
        long totalInstances = instanceRepository.count();
        long completedInstances = instanceRepository.findAll().stream()
                .filter(i -> "COMPLETED".equalsIgnoreCase(i.getStatus()))
                .count();

        // 真实统计数据库实际节点执行所消耗的 Token，绝不凭空伪造
        long totalTokens = nodeExecutionRepository.findAll().stream()
                .mapToLong(e -> e.getCostTokens() != null ? e.getCostTokens() : 0)
                .sum();

        double deepSeekCost = (totalTokens * 0.8) * 0.000016; // ¥0.016 / 1k tokens
        double qwenCost = (totalTokens * 0.2) * 0.000008;     // ¥0.008 / 1k tokens
        double totalCostCny = Math.round((deepSeekCost + qwenCost) * 100.0) / 100.0;

        double hoursSaved = Math.round((completedInstances * 0.75) * 10.0) / 10.0;
        double laborCostSavedCny = Math.round(hoursSaved * 120.0);
        double roiMultiplier = totalCostCny > 0 ? Math.round((laborCostSavedCny / totalCostCny) * 10.0) / 10.0 : 0.0;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalInstances", totalInstances);
        res.put("completedInstances", completedInstances);
        res.put("totalTokensBurned", totalTokens);
        res.put("totalCostCny", totalCostCny);
        res.put("hoursSaved", hoursSaved);
        res.put("laborCostSavedCny", laborCostSavedCny);
        res.put("roiMultiplier", roiMultiplier + "x");
        res.put("activeBudgetQuotaCny", 5000.0);
        res.put("budgetUsedPercent", totalCostCny > 0 ? Math.min(100.0, Math.round((totalCostCny / 5000.0) * 1000.0) / 10.0) : 0.0);

        // 无真实花费时不伪造部门占比
        if (totalCostCny > 0) {
            res.put("departmentBreakdown", List.of(
                Map.of("dept", "业务自动化运行中枢", "sharePercent", 100, "costCny", totalCostCny)
            ));
            res.put("modelCostMatrix", List.of(
                Map.of("model", "生产大模型实际推流", "role", "Production Inference", "tokens", totalTokens, "rate", "按量计费", "cost", totalCostCny)
            ));
        } else {
            res.put("departmentBreakdown", Collections.emptyList());
            res.put("modelCostMatrix", Collections.emptyList());
        }

        res.put("timestamp", LocalDateTime.now().toString());
        return res;
    }
}
