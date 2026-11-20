package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OtlpTraceControllerProtobufTest {
    @Test
    void convertsProtobufBase64IdentifiersToOtlpHex() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OtlpTraceController controller = new OtlpTraceController(null, null, null, mapper, null);
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        byte[] parentSpanId = new byte[8];
        for (int i = 0; i < traceId.length; i++) traceId[i] = (byte) (i + 1);
        for (int i = 0; i < spanId.length; i++) spanId[i] = (byte) (i + 11);
        for (int i = 0; i < parentSpanId.length; i++) parentSpanId[i] = (byte) (i + 21);

        var request = mapper.readTree("""
                {
                  "resourceSpans": [{
                    "scopeSpans": [{
                      "spans": [{
                        "traceId": "%s",
                        "spanId": "%s",
                        "parentSpanId": "%s"
                      }]
                    }]
                  }]
                }
                """.formatted(Base64.getEncoder().encodeToString(traceId),
                Base64.getEncoder().encodeToString(spanId),
                Base64.getEncoder().encodeToString(parentSpanId)));

        Method normalize = OtlpTraceController.class.getDeclaredMethod("normalizeProtobufSpanIds",
                com.fasterxml.jackson.databind.JsonNode.class);
        normalize.setAccessible(true);
        normalize.invoke(controller, request);

        var span = request.path("resourceSpans").get(0).path("scopeSpans").get(0).path("spans").get(0);
        assertEquals("0102030405060708090a0b0c0d0e0f10", span.path("traceId").asText());
        assertEquals("0b0c0d0e0f101112", span.path("spanId").asText());
        assertEquals("15161718191a1b1c", span.path("parentSpanId").asText());
    }
}
