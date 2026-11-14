package com.ownsudo.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record WorkerClaimResponse(
        String actionId,
        String toolName,
        Map<String, Object> arguments,
        String leaseToken,
        LocalDateTime leaseUntil,
        int attemptCount) {
}
