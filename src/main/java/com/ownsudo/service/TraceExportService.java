package com.ownsudo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Optional OTLP fan-out to Phoenix, Langfuse, or any OTLP/HTTP trace receiver.
 * OwnAgent remains the system of record; export failures never fail ingestion.
 */
@Service
public class TraceExportService {
    private static final Logger log = LoggerFactory.getLogger(TraceExportService.class);
    private final RestClient client;
    private final String endpoint;
    private final String authorization;

    public TraceExportService(RestClient.Builder builder,
            @Value("${OWNAGENT_OTLP_EXPORT_ENDPOINT:}") String endpoint,
            @Value("${OWNAGENT_OTLP_EXPORT_AUTHORIZATION:}") String authorization) {
        this.client = builder.build();
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.authorization = authorization == null ? "" : authorization.trim();
    }

    public boolean isEnabled() { return !endpoint.isBlank(); }

    public void forward(JsonNode otlpRequest) {
        if (!isEnabled()) return;
        try {
            RestClient.RequestBodySpec request = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON);
            if (!authorization.isBlank()) request.header("Authorization", authorization);
            request.body(otlpRequest).retrieve().toBodilessEntity();
        } catch (Exception error) {
            log.warn("OTLP export to configured observability backend failed; OwnAgent ingestion remains successful: {}",
                    error.getMessage());
        }
    }
}
