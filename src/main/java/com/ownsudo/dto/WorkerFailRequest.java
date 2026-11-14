package com.ownsudo.dto;

public record WorkerFailRequest(String leaseToken, String errorCode, String errorMessage) {
}
