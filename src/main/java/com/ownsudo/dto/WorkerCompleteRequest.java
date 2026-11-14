package com.ownsudo.dto;

import java.util.Map;

public record WorkerCompleteRequest(String leaseToken, Map<String, Object> output) {
}
