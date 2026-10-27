package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "gateway_route")
public class GatewayRoute {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "model_name", nullable = false, length = 64)
    private String modelName;

    @Column(length = 64)
    private String tier;

    @Column(name = "endpoint_url", length = 255)
    private String endpointUrl;

    @Column(nullable = false)
    private Integer weight = 50;

    @Column(name = "circuit_state", length = 32)
    private String circuitState = "CLOSED"; // CLOSED, HALF_OPEN, OPEN

    @Column(name = "p99_latency_ms")
    private Double p99LatencyMs = 25.0;

    @Column(nullable = false)
    private Boolean healthy = true;

    @Column(name = "active_connections")
    private Integer activeConnections = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    public GatewayRoute() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public String getCircuitState() { return circuitState; }
    public void setCircuitState(String circuitState) { this.circuitState = circuitState; }

    public Double getP99LatencyMs() { return p99LatencyMs; }
    public void setP99LatencyMs(Double p99LatencyMs) { this.p99LatencyMs = p99LatencyMs; }

    public Boolean getHealthy() { return healthy; }
    public void setHealthy(Boolean healthy) { this.healthy = healthy; }

    public Integer getActiveConnections() { return activeConnections; }
    public void setActiveConnections(Integer activeConnections) { this.activeConnections = activeConnections; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}