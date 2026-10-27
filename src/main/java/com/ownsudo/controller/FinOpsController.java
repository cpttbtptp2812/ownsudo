package com.ownsudo.controller;

import com.ownsudo.service.ExactHashCacheService;
import com.ownsudo.service.FinOpsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/finops")
public class FinOpsController {

    @Autowired
    private FinOpsService finOpsService;

    @Autowired
    private ExactHashCacheService exactHashCacheService;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(finOpsService.getFinOpsMetrics());
    }

    @GetMapping("/cache-metrics")
    public ResponseEntity<Map<String, Object>> getCacheMetrics() {
        return ResponseEntity.ok(exactHashCacheService.getFinOpsCacheStats());
    }

    @PostMapping("/cache-clear")
    public ResponseEntity<Map<String, Object>> clearCache() {
        exactHashCacheService.clearAllCache();
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "精确哈希降本缓存已全部清空重置"
        ));
    }
}
