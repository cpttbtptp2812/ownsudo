package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.SystemConfigEntity;
import com.ownsudo.repository.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class LlmClientService {

    @Autowired
    private SystemConfigRepository configRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getConfig(String key, String defaultVal) {
        return configRepository.findById(key)
                .map(SystemConfigEntity::getConfigValue)
                .orElse(System.getenv().getOrDefault(key, defaultVal));
    }

    public void setConfig(String key, String value) {
        configRepository.save(new SystemConfigEntity(key, value));
    }

    /**
     * 真实大模型端点心跳探针 (超时 1500ms 快速熔断)
     * 严防向前端透出虚假的绿灯在线假象
     */
    public Map<String, Object> probeModelConnectivity() {
        String baseUrl = getConfig("LLM_BASE_URL", "http://localhost:11434/v1");
        String model = getConfig("LLM_MODEL", "deepseek-coder");
        String apiKey = getConfig("LLM_API_KEY", getConfig("DEEPSEEK_API_KEY", ""));

        boolean isLocal = baseUrl.contains("11434") || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");

        // 1. 若配置为云端且无有效 API Key，直接判定未连通
        if (!isLocal && (apiKey == null || apiKey.trim().isEmpty() || apiKey.startsWith("sk-***"))) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("connected", false);
            res.put("provider", "云端大模型");
            res.put("model", model);
            res.put("status", "NO_API_KEY");
            res.put("message", "未配置云端 API Key，当前由高仿真离线沙箱接管");
            return res;
        }

        // 2. 真实 HTTP 轻量嗅探
        long start = System.currentTimeMillis();
        try {
            String testUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            if (baseUrl.contains("11434")) {
                testUrl = "http://localhost:11434/api/tags";
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .timeout(Duration.ofMillis(isLocal ? 2000 : 6000))
                    .GET();
            if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-***")) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            Map<String, Object> res = new LinkedHashMap<>();
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                res.put("connected", true);
                res.put("provider", isLocal ? "本地 Ollama" : "云端大模型");
                res.put("model", model);
                res.put("latencyMs", latency);
                res.put("status", "CONNECTED");
                res.put("message", "模型服务连接正常");
            } else {
                res.put("connected", false);
                res.put("provider", isLocal ? "本地 Ollama" : "云端大模型");
                res.put("model", model);
                res.put("status", "HTTP_" + resp.statusCode());
                res.put("message", "模型服务返回异常 HTTP 状态码: " + resp.statusCode());
            }
            return res;
        } catch (Exception e) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("connected", false);
            res.put("provider", isLocal ? "本地 Ollama" : "云端大模型");
            res.put("model", model);
            res.put("status", "DISCONNECTED");
            res.put("message", isLocal ? "本地 Ollama (11434) 端口未启动" : "网络超时或不可达");
            return res;
        }
    }

    public void streamChat(
            String customModel,
            Double customTemperature,
            List<Map<String, String>> messages,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            AtomicBoolean isClientConnected
    ) throws Exception {
        List<Map<String, Object>> objList = new ArrayList<>();
        for (Map<String, String> m : messages) {
            objList.add(new LinkedHashMap<>(m));
        }
        streamChat(customModel, customTemperature, objList, onDelta, onReasoning, null, isClientConnected);
    }

    public void streamChat(
            String customModel,
            Double customTemperature,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            java.util.function.BiConsumer<Integer, Integer> onUsage,
            AtomicBoolean isClientConnected
    ) throws Exception {
        String configuredBaseUrl = getConfig("LLM_BASE_URL", "https://ollama.com/v1");
        String configuredModel = getConfig("LLM_MODEL", "gemma4:31b-cloud");
        String apiKey = getConfig("LLM_API_KEY", getConfig("DEEPSEEK_API_KEY", ""));

        boolean isLocalOllama = configuredBaseUrl.contains("11434");
        boolean isOllamaCloud = configuredBaseUrl.contains("ollama.com");

        // 智能模型对齐：如果在 ollama.com 云端，但传入了仅在本地运行的旧模型名 deepseek-r1:8b，自动校正为云端支持的模型
        String model;
        if (isOllamaCloud && (customModel == null || customModel.isBlank() || customModel.equals("deepseek-r1:8b") || customModel.equals("deepseek-chat"))) {
            model = configuredModel;
        } else if (customModel != null && !customModel.isBlank() && !customModel.equals("deepseek-chat")) {
            model = customModel;
        } else {
            model = configuredModel;
        }
        double temperature = customTemperature != null ? customTemperature : 0.7;

        // 如果是云端闭源模型且没有配置有效 API Key，自动启用服务诊断提示
        if (!isLocalOllama && (apiKey == null || apiKey.trim().isEmpty() || apiKey.startsWith("sk-***"))) {
            streamDiagnosticFallback(messages, onDelta, onReasoning, onUsage, isClientConnected);
            return;
        }

        String baseUrl = configuredBaseUrl;
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String endpoint = baseUrl + "chat/completions";

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("model", model);
        reqBody.put("messages", messages);
        reqBody.put("stream", true);
        reqBody.put("temperature", temperature);
        reqBody.put("stream_options", Map.of("include_usage", true));

        String jsonPayload = objectMapper.writeValueAsString(reqBody);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        onReasoning.accept("正在向推理端点 (" + endpoint + ") 发起流式推理 [模型: " + model + ", 温度: " + temperature + "]...");

        // 【致命雷 2 防线：物理级 Cancel 拦截】使用 sendAsync，若连接建立期间客户端断开，立即调用 future.cancel(true) 物理中断 TCP/HTTP 连接
        java.util.concurrent.CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> future =
                httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofLines());

        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            while (!future.isDone()) {
                if (!isClientConnected.get()) {
                    future.cancel(true);
                    System.out.println("[LlmClientService] 客户端在连接建立/握手阶段主动断开，已执行 future.cancel(true) 物理切断连接！");
                    return;
                }
                Thread.sleep(30);
            }
            response = future.get();
        } catch (Exception e) {
            future.cancel(true);
            if (!isClientConnected.get()) return;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ConnectException) {
                System.out.println("[LlmClientService] 上游端点无法连通 (" + endpoint + ")，自动启用【服务诊断提示】引导用户配置");
                streamDiagnosticFallback(messages, onDelta, onReasoning, onUsage, isClientConnected);
                return;
            }
            throw e;
        }

        if (response.statusCode() != 200) {
            System.out.println("[LlmClientService] 上游返回异常状态码 " + response.statusCode() + "，自动降级至【服务诊断提示】");
            streamDiagnosticFallback(messages, onDelta, onReasoning, onUsage, isClientConnected);
            return;
        }

        try (java.util.stream.Stream<String> stream = response.body()) {
            java.util.Iterator<String> it = stream.iterator();
            while (it.hasNext()) {
                if (!isClientConnected.get()) {
                    System.out.println("[LlmClientService] 客户端连接已断开，立即 close HTTP 响应流中止上游传输");
                    break;
                }
                String line = it.next();
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    try {
                        JsonNode root = objectMapper.readTree(data);

                        // 捕获真实 usage (若上游接口返回)
                        if (root.has("usage") && !root.get("usage").isNull()) {
                            JsonNode usageNode = root.get("usage");
                            int pTokens = usageNode.path("prompt_tokens").asInt(0);
                            int cTokens = usageNode.path("completion_tokens").asInt(0);
                            if (onUsage != null && (pTokens > 0 || cTokens > 0)) {
                                onUsage.accept(pTokens, cTokens);
                            }
                        }

                        JsonNode choices = root.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).path("delta");
                            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                                String r = delta.get("reasoning_content").asText("");
                                if (!r.isEmpty()) onReasoning.accept(r);
                            }
                            if (delta.has("content") && !delta.get("content").isNull()) {
                                String c = delta.get("content").asText("");
                                if (!c.isEmpty()) onDelta.accept(c);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * 【工具调用中枢】向大模型发送包含 tools 的非流式请求，用于检测模型是否要调用本地工具
     */
    public JsonNode callChatCompletionsWithTools(
            String customModel,
            Double customTemperature,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            AtomicBoolean isClientConnected
    ) throws Exception {
        String configuredBaseUrl = getConfig("LLM_BASE_URL", "https://ollama.com/v1");
        String configuredModel = getConfig("LLM_MODEL", "gemma4:31b-cloud");
        String apiKey = getConfig("LLM_API_KEY", getConfig("DEEPSEEK_API_KEY", ""));

        String model = (customModel != null && !customModel.isBlank() && !customModel.equals("deepseek-chat")) ? customModel : configuredModel;
        double temperature = customTemperature != null ? customTemperature : 0.7;

        String baseUrl = configuredBaseUrl;
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String endpoint = baseUrl + "chat/completions";

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("model", model);
        reqBody.put("messages", messages);
        reqBody.put("stream", false);
        reqBody.put("temperature", temperature);
        if (tools != null && !tools.isEmpty()) {
            reqBody.put("tools", tools);
            reqBody.put("tool_choice", "auto");
        }

        String jsonPayload = objectMapper.writeValueAsString(reqBody);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        java.util.concurrent.CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response;
        try {
            while (!future.isDone()) {
                if (!isClientConnected.get()) {
                    future.cancel(true);
                    return null;
                }
                Thread.sleep(30);
            }
            response = future.get();
            if (response.statusCode() == 200) {
                return objectMapper.readTree(response.body());
            }
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
        return null;
    }

        public float[] getEmbeddings(String text) {
        if (text == null || text.isBlank()) return null;

        String configuredEmbedBase = getConfig("EMBEDDING_BASE_URL", "").trim();
        String llmBase = getConfig("LLM_BASE_URL", "http://localhost:11434/v1").trim();

        String targetBaseUrl;
        if (!configuredEmbedBase.isEmpty()) {
            targetBaseUrl = configuredEmbedBase;
        } else {
            // 关键保护：若 LLM_BASE_URL 是 DeepSeek 等无 embedding 接口的纯对话服务商，坚决不打向 deepseek.com/v1/embeddings
            if (llmBase.toLowerCase().contains("deepseek.com")) {
                // 自动解耦并回退至本地 Ollama 专用端点，避免 404
                targetBaseUrl = "http://localhost:11434/v1";
            } else {
                targetBaseUrl = llmBase;
            }
        }

        String embedModel = getConfig("EMBEDDING_MODEL", "").trim();
        if (embedModel.isEmpty()) {
            if (targetBaseUrl.contains("openai.com")) {
                embedModel = "text-embedding-3-small";
            } else if (targetBaseUrl.contains("siliconflow.cn")) {
                embedModel = "BAAI/bge-m3";
            } else {
                embedModel = "nomic-embed-text";
            }
        }

        String apiKey = getConfig("EMBEDDING_API_KEY", "").trim();
        if (apiKey.isEmpty()) {
            if (targetBaseUrl.contains("11434")) {
                apiKey = "ollama";
            } else {
                apiKey = getConfig("LLM_API_KEY", getConfig("DEEPSEEK_API_KEY", ""));
            }
        }

        try {
            String baseUrl = targetBaseUrl;
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String endpoint = baseUrl + "embeddings";

            Map<String, Object> reqBody = Map.of(
                "model", embedModel,
                "input", text
            );

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(4))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(reqBody)));

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode data = root.path("data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode embNode = data.get(0).path("embedding");
                    if (embNode.isArray() && embNode.size() > 0) {
                        float[] vector = new float[embNode.size()];
                        for (int i = 0; i < embNode.size(); i++) {
                            vector[i] = (float) embNode.get(i).asDouble();
                        }
                        return vector;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public Map<String, Object> testLlmConnection(String customBaseUrl, String customApiKey, String customModel) {
        String baseUrl = (customBaseUrl != null && !customBaseUrl.isBlank()) ? customBaseUrl.trim() : getConfig("LLM_BASE_URL", "https://api.deepseek.com/v1");
        String apiKey = (customApiKey != null && !customApiKey.isBlank()) ? customApiKey.trim() : getConfig("LLM_API_KEY", "");
        String model = (customModel != null && !customModel.isBlank()) ? customModel.trim() : getConfig("LLM_MODEL", "deepseek-chat");

        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String endpoint = baseUrl + "chat/completions";

        Map<String, Object> reqBody = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", "hi")),
            "max_tokens", 2,
            "stream", false
        );

        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(reqBody)));

            if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("ollama")) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (resp.statusCode() == 200) {
                return Map.of("ok", true, "status", 200, "latencyMs", latency, "msg", "连接成功！大模型响应正常 (" + latency + "ms)");
            } else {
                String detail = resp.body() != null ? resp.body() : "";
                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(detail);
                    if (node.has("error")) {
                        com.fasterxml.jackson.databind.JsonNode errNode = node.get("error");
                        if (errNode.isObject() && errNode.has("message")) {
                            detail = errNode.get("message").asText();
                        } else if (errNode.isTextual()) {
                            detail = errNode.asText();
                        }
                    }
                } catch (Exception ignored) {}
                String tip = "服务商返回 HTTP " + resp.statusCode() + ": " + detail;
                return Map.of("ok", false, "status", resp.statusCode(), "msg", tip, "detail", detail);
            }
        } catch (ConnectException ce) {
            boolean isLocal = endpoint.contains("11434");
            String msg = isLocal
                ? "无法连接到本地 11434 端口。若使用本地 Ollama，请确保已启动 ollama 软件并运行模型；若使用云端服务，请切换至对应供应商并填入 API Key。"
                : "网络连接失败：无法连通目标端点 " + endpoint + "，请检查网络或代理。";
            return Map.of("ok", false, "status", 0, "msg", msg);
        } catch (Exception e) {
            return Map.of("ok", false, "status", 0, "msg", "请求异常：" + e.getMessage());
        }
    }


    public Map<String, Object> fetchRemoteModels(String customBaseUrl, String customApiKey) {
        String baseUrl = (customBaseUrl != null && !customBaseUrl.isBlank()) ? customBaseUrl.trim() : getConfig("LLM_BASE_URL", "https://api.deepseek.com/v1");
        String apiKey = (customApiKey != null && !customApiKey.isBlank()) ? customApiKey.trim() : getConfig("LLM_API_KEY", "");

        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String endpoint = baseUrl + "models";

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET();

            if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("ollama")) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
                List<String> modelList = new java.util.ArrayList<>();
                if (root.has("data") && root.get("data").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode item : root.get("data")) {
                        if (item.has("id")) {
                            modelList.add(item.get("id").asText());
                        }
                    }
                } else if (root.has("models") && root.get("models").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode item : root.get("models")) {
                        if (item.has("name")) {
                            modelList.add(item.get("name").asText());
                        } else if (item.has("id")) {
                            modelList.add(item.get("id").asText());
                        }
                    }
                }
                return Map.of("ok", true, "count", modelList.size(), "models", modelList, "msg", "成功拉取到 " + modelList.size() + " 个最新可用模型");
            } else {
                String detail = resp.body() != null ? resp.body() : "";
                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(detail);
                    if (node.has("error") && node.get("error").has("message")) {
                        detail = node.get("error").get("message").asText();
                    }
                } catch (Exception ignored) {}
                return Map.of("ok", false, "status", resp.statusCode(), "msg", "拉取失败 HTTP " + resp.statusCode() + ": " + detail);
            }
        } catch (Exception e) {
            return Map.of("ok", false, "msg", "无法拉取模型列表: " + e.getMessage());
        }
    }

    /**
     * 【端点未就绪降级提示】
     * 当外部大模型端点未就绪或未配 Key 时，诚实输出诊断信息与引导配置，绝不假造虚假长文或虚假 QPS 数据。
     */
    public void streamDiagnosticFallback(
            List<Map<String, Object>> messages,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            java.util.function.BiConsumer<Integer, Integer> onUsage,
            AtomicBoolean isClientConnected
    ) {
        if (onReasoning != null && isClientConnected.get()) {
            onReasoning.accept("检测到当前大模型推理端点未配置有效 API Key 或网络未就绪，输出服务状态诊断...");
        }

        String notice = "> ⚠️ **【大模型服务未就绪】**\n\n"
                + "系统检测到当前大模型端点尚未连通或未配置有效 API Key，无法完成实时 LLM 推理。\n\n"
                + "**如何启用实时模型推理：**\n"
                + "1. **配置云端大模型**：前往左侧导航栏 **「设置 / 治理」**，填入通义千问 (DashScope)、DeepSeek 或 OpenAI 的 API Key 并保存；\n"
                + "2. **启动本地 Ollama**：在本地终端启动 `ollama run deepseek-r1:8b`（默认端口 11434），系统会自动连通本地模型；\n"
                + "3. **知识库 RAG 与缓存依然可用**：若命中企业知识库或精确哈希，上方已真实呈现知识切片与毫秒级降本缓存。\n";

        int chunkSize = 8;
        for (int i = 0; i < notice.length(); i += chunkSize) {
            if (!isClientConnected.get()) {
                break;
            }
            int end = Math.min(i + chunkSize, notice.length());
            onDelta.accept(notice.substring(i, end));
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {}
        }

        if (onUsage != null) {
            onUsage.accept(0, 0);
        }
    }
}
