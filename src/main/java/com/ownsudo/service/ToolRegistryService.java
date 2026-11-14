package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.dto.RegisterToolRequest;
import com.ownsudo.entity.AgentToolEntity;
import com.ownsudo.repository.AgentToolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ToolRegistryService {
    private static final Set<String> RISK_LEVELS = Set.of("READ_ONLY","LOW","MEDIUM","HIGH","CRITICAL");
    private final AgentToolRepository repository;
    private final ObjectMapper mapper;
    public ToolRegistryService(AgentToolRepository repository, ObjectMapper mapper) { this.repository=repository; this.mapper=mapper; }

    @Transactional
    public AgentToolEntity register(String agentId, RegisterToolRequest request) {
        if (agentId == null || agentId.isBlank() || request == null) throw new IllegalArgumentException("Authenticated Agent and request are required");
        String name = request.getToolName()==null ? "" : request.getToolName().trim();
        if (!name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("toolName must be 1-128 valid identifier characters");
        String description = request.getDescription()==null ? "" : request.getDescription().trim();
        if (description.isBlank() || description.length()>512) throw new IllegalArgumentException("description is required and must not exceed 512 characters");
        String risk = request.getRiskLevel()==null ? "" : request.getRiskLevel().trim().toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(risk)) throw new IllegalArgumentException("riskLevel must be READ_ONLY, LOW, MEDIUM, HIGH, or CRITICAL");
        JsonNode schema = mapper.valueToTree(request.getInputSchema());
        if (schema==null || !schema.isObject() || !"object".equals(schema.path("type").asText())) throw new IllegalArgumentException("inputSchema must be a JSON Schema object with type=object");
        JsonNode properties = schema.path("properties");
        if (!properties.isMissingNode() && !properties.isObject()) throw new IllegalArgumentException("inputSchema.properties must be an object");
        JsonNode required = schema.path("required");
        if (!required.isMissingNode() && !required.isArray()) throw new IllegalArgumentException("inputSchema.required must be an array");
        if (required.isArray()) for (JsonNode item : required) {
            if (!item.isTextual() || !properties.has(item.asText())) throw new IllegalArgumentException("Every required field must exist in inputSchema.properties");
        }
        AgentToolEntity tool = repository.findByAgentIdAndToolName(agentId,name).orElseGet(AgentToolEntity::new);
        if (tool.getId()==null) tool.setId("tool-"+UUID.randomUUID());
        tool.setAgentId(agentId); tool.setToolName(name); tool.setDescription(description);
        try { tool.setInputSchemaJson(mapper.writeValueAsString(schema)); }
        catch (Exception e) { throw new IllegalArgumentException("inputSchema cannot be serialized",e); }
        tool.setRiskLevel(risk); tool.setEnabled(request.getEnabled()==null || request.getEnabled());
        return repository.save(tool);
    }

    @Transactional(readOnly=true)
    public List<AgentToolEntity> list(String agentId) { return repository.findAllByAgentIdOrderByToolNameAsc(agentId); }

    @Transactional(readOnly=true)
    public Optional<AgentToolEntity> find(String agentId, String toolName) { return repository.findByAgentIdAndToolName(agentId,toolName); }

    public void validateArguments(AgentToolEntity tool, Map<String,Object> arguments) {
        JsonNode schema;
        try { schema=mapper.readTree(tool.getInputSchemaJson()); }
        catch(Exception e) { throw new IllegalStateException("Registered tool schema is invalid",e); }
        JsonNode properties=schema.path("properties");
        JsonNode required=schema.path("required");
        if(required.isArray()) for(JsonNode field:required) {
            String name=field.asText();
            if(!arguments.containsKey(name) || arguments.get(name)==null) throw new IllegalArgumentException("Missing required tool argument: "+name);
        }
        boolean allowAdditional=schema.path("additionalProperties").asBoolean(true);
        if(!allowAdditional) for(String key:arguments.keySet()) if(!properties.has(key)) throw new IllegalArgumentException("Unknown tool argument: "+key);
        Iterator<Map.Entry<String,JsonNode>> fields=properties.fields();
        while(fields.hasNext()) {
            var field=fields.next(); if(!arguments.containsKey(field.getKey())) continue;
            Object value=arguments.get(field.getKey()); String expected=field.getValue().path("type").asText();
            boolean valid=switch(expected) {
                case "string" -> value instanceof String;
                case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
                case "number" -> value instanceof Number;
                case "boolean" -> value instanceof Boolean;
                case "object" -> value instanceof Map<?,?>;
                case "array" -> value instanceof List<?>;
                default -> true;
            };
            if(!valid) throw new IllegalArgumentException("Tool argument '"+field.getKey()+"' must be of type "+expected);
        }
    }
}