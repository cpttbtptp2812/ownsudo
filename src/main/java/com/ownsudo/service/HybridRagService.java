package com.ownsudo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.KnowledgeChunk;
import com.ownsudo.repository.KnowledgeChunkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import jakarta.persistence.criteria.Predicate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产级 RAG 混合检索服务 (HybridRagService)
 * 严格落实 25K-30K 工业级规范：
 * 1. 切片分块优先按段落换行 \n\n 与标点断句，超长按 500 字符硬截断，50 字符重叠窗口；
 * 2. 向量入库离线化：上传时算好落库，检索绝不现算切片向量，Embedding 失败明确记为无向量（绝不造假 128 维伪哈希）；
 * 3. 两阶段召回：BM25 关键词粗筛（仅候选 BM25 > 0 的切片，上限 50 条）+ 预存 Embedding 内存余弦精排；
 * 4. 向量维度对齐校验：维度不符严格返回 0.0，杜绝截断虚假计算；
 * 5. 硬阈值防幻觉：设定相似度硬阈值 0.65，低于此门槛判定为噪音。
 */
@Service
public class HybridRagService {

    private static final int RRF_K = 60;
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    // 相似度硬阈值：余弦相似度必须 >= 0.65 方可被判定为高置信切片注入上下文
    public static final double COSINE_THRESHOLD = 0.65;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private LlmClientService llmClientService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class RagChunk {
        private String chunkId;
        private String projectName;
        private String section;
        private String text;
        private double denseScore;
        private double sparseScore;
        private double rrfScore;
        private String vectorMode;
        private String vectorModel;

        public RagChunk(String chunkId, String projectName, String section, String text,
                double denseScore, double sparseScore, double rrfScore,
                String vectorMode, String vectorModel) {
            this.chunkId = chunkId;
            this.projectName = projectName;
            this.section = section;
            this.text = text;
            this.denseScore = denseScore;
            this.sparseScore = sparseScore;
            this.rrfScore = rrfScore;
            this.vectorMode = vectorMode;
            this.vectorModel = vectorModel;
        }

        public String getChunkId() {
            return chunkId;
        }

        public String getProjectName() {
            return projectName;
        }

        public String getSection() {
            return section;
        }

        public String getText() {
            return text;
        }

        public double getDenseScore() {
            return denseScore;
        }

        public double getSparseScore() {
            return sparseScore;
        }

        public double getRrfScore() {
            return rrfScore;
        }

        public String getVectorMode() {
            return vectorMode;
        }

        public String getVectorModel() {
            return vectorModel;
        }
    }

    public static class SearchResult {
        private final List<RagChunk> chunks;
        private final int totalDocs;
        private final int bm25Candidates;
        private final int noiseFiltered;
        private final int validCount;
        private final long costMs;
        private final boolean isRealVector;
        private final String mode;

        public SearchResult(List<RagChunk> chunks, int totalDocs, int bm25Candidates, int noiseFiltered,
                            int validCount, long costMs, boolean isRealVector, String mode) {
            this.chunks = chunks != null ? chunks : Collections.emptyList();
            this.totalDocs = totalDocs;
            this.bm25Candidates = bm25Candidates;
            this.noiseFiltered = noiseFiltered;
            this.validCount = validCount;
            this.costMs = costMs;
            this.isRealVector = isRealVector;
            this.mode = mode;
        }

        public List<RagChunk> getChunks() { return chunks; }
        public int getTotalDocs() { return totalDocs; }
        public int getBm25Candidates() { return bm25Candidates; }
        public int getNoiseFiltered() { return noiseFiltered; }
        public int getValidCount() { return validCount; }
        public long getCostMs() { return costMs; }
        public boolean isRealVector() { return isRealVector; }
        public String getMode() { return mode; }
    }

    /**
     * 【长文本切片工程】
     * 规则：优先按段落换行 \n\n 寻找切分点；超长按句号。！等标点截断；50 字符 Overlap 避免语义断裂。
     */
    public List<String> chunkText(String fullText) {
        if (fullText == null || fullText.isBlank())
            return Collections.emptyList();
        String cleaned = fullText.trim().replace("\r\n", "\n").replace("\r", "\n");

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = cleaned.split("\n\n+");
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty())
                continue;

            if (p.length() > CHUNK_SIZE) {
                // 当前段落本身超过 500 字符，先结算前面累积的内容
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }

                // 对超长段落按标点与断句二次分块
                int start = 0;
                int pLen = p.length();
                while (start < pLen) {
                    int end = Math.min(start + CHUNK_SIZE, pLen);
                    if (end < pLen) {
                        int boundary = -1;
                        for (int i = end; i >= Math.max(start + CHUNK_SIZE - 100, start); i--) {
                            char c = p.charAt(i);
                            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '；' || c == ';') {
                                boundary = i + 1;
                                break;
                            }
                        }
                        if (boundary > start) {
                            end = boundary;
                        }
                    }
                    String subChunk = p.substring(start, end).trim();
                    if (!subChunk.isEmpty()) {
                        chunks.add(subChunk);
                    }
                    if (end >= pLen)
                        break;
                    start = Math.max(start + 1, end - CHUNK_OVERLAP);
                }
            } else {
                // 如果段落累加未超过 CHUNK_SIZE，则合并
                if (currentChunk.length() + p.length() + 2 <= CHUNK_SIZE) {
                    if (currentChunk.length() > 0)
                        currentChunk.append("\n\n");
                    currentChunk.append(p);
                } else {
                    // 超出限制，封包前一个切片，并保留 50 字符重叠
                    chunks.add(currentChunk.toString().trim());
                    String prev = currentChunk.toString();
                    String overlap = prev.length() > CHUNK_OVERLAP
                            ? prev.substring(prev.length() - CHUNK_OVERLAP).trim()
                            : "";
                    currentChunk.setLength(0);
                    if (!overlap.isEmpty()) {
                        currentChunk.append(overlap).append("\n\n");
                    }
                    currentChunk.append(p);
                }
            }
        }

        if (currentChunk.length() > 0 && !currentChunk.toString().trim().isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 【入库流水线】长文上传切片并完成向量离线落库，搜索时绝不再现算
     * 返回结构化统计：总片数、成功片数、失败片数及切片实体列表
     */
    public Map<String, Object> processAndIngestDocument(String docTitle, String fullContent, String keywords,
            String category) {
        List<String> chunkTexts = chunkText(fullContent);
        if (chunkTexts.isEmpty()) {
            return Map.of("totalChunks", 0, "successChunks", 0, "failedChunks", 0, "chunks", Collections.emptyList());
        }

        List<KnowledgeChunk> savedChunks = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        int index = 0;

        for (String cText : chunkTexts) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setId("chunk-" + UUID.randomUUID().toString().substring(0, 8));
            kc.setDocTitle(docTitle != null && !docTitle.isBlank() ? docTitle : "未命名文档");
            kc.setChunkIndex(index++);
            kc.setContent(cText);
            kc.setKeywords(keywords != null && !keywords.isBlank() ? keywords : "企业切片, 真实召回");
            kc.setCategory(category != null ? category : "ENTERPRISE");

            // 离线向量化计算：调用 Embedding API，若失败则记为 null，绝不造假 128 维伪哈希
            try {
                float[] vector = llmClientService.getEmbeddings(cText);
                if (vector != null && vector.length > 0) {
                    kc.setEmbeddingJson(objectMapper.writeValueAsString(vector));
                    successCount++;
                } else {
                    kc.setEmbeddingJson(null);
                    failedCount++;
                    System.err.println("[RAG] 切片 #" + kc.getChunkIndex() + " 向量生成失败 (Embedding 端点返回空)，已入库但无向量");
                }
            } catch (Exception e) {
                kc.setEmbeddingJson(null);
                failedCount++;
                System.err.println("[RAG] 切片 #" + kc.getChunkIndex() + " 向量生成异常: " + e.getMessage());
            }

            savedChunks.add(knowledgeChunkRepository.save(kc));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalChunks", savedChunks.size());
        result.put("successChunks", successCount);
        result.put("failedChunks", failedCount);
        result.put("chunks", savedChunks);
        return result;
    }

    /**
     * 【两阶段混合检索】
     * 阶段一: 粗筛全库不含 embedding_json 的轻量元数据，以全库真实文档数做 N 计算 BM25，
     *         筛选 BM25 > 0 的前 50 个 ID；若关键词一个都没命中，坚决返回空，不补按顺序的切片。
     * 阶段二: 仅对这 50 个切片回表查询 embedding_json 并在内存中计算余弦相似度。
     * 阶段三: 先丢掉余弦低于 0.65 的噪音切片；仅对通过阈值的高置信切片进行 RRF 融合打分并取 Top-K。
     */
    public List<RagChunk> hybridSearch(String rawQuery, int limit) {
        return hybridSearchWithStats(rawQuery, limit).getChunks();
    }

    /**
     * 【两阶段混合检索（带完整审计统计）】
     * 阶段一: 粗筛全库不含 embedding_json 的轻量元数据，以全库真实文档数做 N 计算 BM25，
     *         筛选 BM25 > 0 的前 50 个 ID；若关键词一个都没命中，坚决返回空。
     * 阶段二: 仅对这 50 个切片回表查询 embedding_json 并在内存中计算余弦相似度。
     * 阶段三: 先硬过滤低于 0.65 的噪音切片；仅对通过阈值的高置信切片进行 RRF 融合打分并取 Top-K。
     */
    public SearchResult hybridSearchWithStats(String rawQuery, int limit) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return new SearchResult(Collections.emptyList(), 0, 0, 0, 0, 0, false, "EMPTY_QUERY");
        }

        long searchStart = System.currentTimeMillis();
        String query = rawQuery.trim();

        // 1. 查出不含 embedding_json 的轻量字段 (全库条数做 N)
        List<com.ownsudo.dto.ChunkMetadata> allMeta = knowledgeChunkRepository.findAllLightweightChunks();
        if (allMeta == null || allMeta.isEmpty()) {
            return new SearchResult(Collections.emptyList(), 0, 0, 0, 0, 0, false, "NO_DATA");
        }

        long bm25Start = System.currentTimeMillis();
        OkapiBM25Engine bm25Engine = new OkapiBM25Engine(allMeta, 1.2, 0.75, this);
        Map<String, Double> bm25Scores = new HashMap<>();
        double maxBm25 = 0.0;

        for (com.ownsudo.dto.ChunkMetadata meta : allMeta) {
            double score = bm25Engine.computeBM25(query, meta.getId());
            if (score > 0.0) {
                bm25Scores.put(meta.getId(), score);
                if (score > maxBm25) maxBm25 = score;
            }
        }

        // 关键词一个都没命中时，直接返回空！绝不按插入顺序补 50 条！
        if (bm25Scores.isEmpty()) {
            long totalCost = System.currentTimeMillis() - searchStart;
            System.out.println("[HybridRAG] 关键词未命中任何切片 (BM25=0)，直接返回空，总耗时: " + totalCost + "ms");
            return new SearchResult(Collections.emptyList(), allMeta.size(), 0, 0, 0, totalCost, false, "BM25_ZERO");
        }

        // 按 BM25 得分从高到低排序，取得分大于 0 的前 50 个 ID
        List<String> top50Ids = bm25Scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(50)
                .map(Map.Entry::getKey)
                .toList();

        long bm25Cost = System.currentTimeMillis() - bm25Start;

        // 2. 仅回表将这 <= 50 条候选切片的 embedding_json 读进内存
        long dbFetchStart = System.currentTimeMillis();
        List<KnowledgeChunk> candidateChunks = knowledgeChunkRepository.findAllById(top50Ids);
        long dbFetchCost = System.currentTimeMillis() - dbFetchStart;

        if (candidateChunks.isEmpty()) {
            return new SearchResult(Collections.emptyList(), allMeta.size(), top50Ids.size(), 0, 0, System.currentTimeMillis() - searchStart, false, "CANDIDATE_EMPTY");
        }

        // 3. 计算向量余弦相似度 (Query 算一次向量，候选切片读库中预存 embedding)
        long vectorStart = System.currentTimeMillis();
        float[] queryVector = llmClientService.getEmbeddings(query);
        boolean isRealVector = (queryVector != null && queryVector.length > 0);

        Map<String, Double> denseScores = new HashMap<>();
        if (isRealVector) {
            for (KnowledgeChunk c : candidateChunks) {
                float[] chunkVec = null;
                if (c.getEmbeddingJson() != null && !c.getEmbeddingJson().isBlank()) {
                    try {
                        chunkVec = objectMapper.readValue(c.getEmbeddingJson(), new TypeReference<float[]>() {});
                    } catch (Exception ignored) {}
                }

                double cosine = 0.0;
                if (chunkVec != null && chunkVec.length > 0) {
                    cosine = computeCosineSimilarity(queryVector, chunkVec);
                }
                denseScores.put(c.getId(), cosine);
            }
        }
        long vectorCost = System.currentTimeMillis() - vectorStart;

        // 4. 【关键修复】：若具备有效向量计算，先硬过滤掉低于 0.65 的噪音切片；若无外部向量模型，则走纯 BM25 稀疏高置信度召回
        List<KnowledgeChunk> highConfCandidates;
        int noiseFiltered = 0;
        if (isRealVector) {
            highConfCandidates = candidateChunks.stream()
                    .filter(c -> denseScores.getOrDefault(c.getId(), 0.0) >= COSINE_THRESHOLD)
                    .toList();
            noiseFiltered = candidateChunks.size() - highConfCandidates.size();
        } else {
            // 降级模式：无外部向量服务时，由纯 BM25 稀疏检索保障真实业务召回
            highConfCandidates = candidateChunks;
            noiseFiltered = 0;
        }

        if (highConfCandidates.isEmpty()) {
            long totalCost = System.currentTimeMillis() - searchStart;
            System.out.println("[HybridRAG] BM25 粗筛命中 " + candidateChunks.size() + " 条，但余弦相似度均低于 0.65 阈值，判定为噪音全部过滤，总耗时: " + totalCost + "ms");
            return new SearchResult(Collections.emptyList(), allMeta.size(), candidateChunks.size(), candidateChunks.size(), 0, totalCost, isRealVector, isRealVector ? "ALL_NOISE_FILTERED" : "BM25_EMPTY");
        }

        // 5. 对通过了 0.65 阈值的优质切片做 RRF 融合排序
        List<KnowledgeChunk> validForRrf = new ArrayList<>(highConfCandidates);

        validForRrf.sort((a, b) -> Double.compare(bm25Scores.getOrDefault(b.getId(), 0.0),
                bm25Scores.getOrDefault(a.getId(), 0.0)));
        Map<String, Integer> sparseRanks = new HashMap<>();
        for (int i = 0; i < validForRrf.size(); i++)
            sparseRanks.put(validForRrf.get(i).getId(), i + 1);

        validForRrf.sort((a, b) -> Double.compare(denseScores.getOrDefault(b.getId(), 0.0),
                denseScores.getOrDefault(a.getId(), 0.0)));
        Map<String, Integer> denseRanks = new HashMap<>();
        for (int i = 0; i < validForRrf.size(); i++)
            denseRanks.put(validForRrf.get(i).getId(), i + 1);

        double safeMaxBm25 = maxBm25 > 0 ? maxBm25 : 1.0;
        List<RagChunk> resultList = new ArrayList<>();

        for (KnowledgeChunk c : validForRrf) {
            String cid = c.getId();
            int rankSparse = sparseRanks.getOrDefault(cid, validForRrf.size() + 1);
            int rankDense = denseRanks.getOrDefault(cid, validForRrf.size() + 1);

            double rrfScore = (1.0 / (RRF_K + rankSparse)) + (1.0 / (RRF_K + rankDense));
            double normalizedBm25 = Math.min(1.0, bm25Scores.getOrDefault(cid, 0.0) / safeMaxBm25);
            double realDenseCosine = denseScores.getOrDefault(cid, 0.0);
            double displayScore = isRealVector ? realDenseCosine : normalizedBm25;

            resultList.add(new RagChunk(
                    c.getId(),
                    c.getDocTitle() + " [#分块" + c.getChunkIndex() + "]",
                    "第 " + c.getChunkIndex() + " 块",
                    c.getContent(),
                    Double.parseDouble(String.format(Locale.US, "%.4f", displayScore)),
                    Double.parseDouble(String.format(Locale.US, "%.4f", normalizedBm25)),
                    Double.parseDouble(String.format(Locale.US, "%.4f", rrfScore)),
                    isRealVector ? (c.getEmbeddingJson() != null ? "TRUE_VECTOR" : "NO_VECTOR") : "BM25_MATCH",
                    isRealVector ? "text-embedding" : "bm25-sparse"));
        }

        resultList.sort((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()));
        List<RagChunk> topResults = resultList.stream().limit(limit).toList();

        long totalCost = System.currentTimeMillis() - searchStart;
        System.out.println("[HybridRAG] 两阶段检索完成: 全库=" + allMeta.size() + "片, BM25粗筛=" + top50Ids.size() + "片 (耗时" + bm25Cost + "ms), 回表读取=" + candidateChunks.size() + "片 (耗时" + dbFetchCost + "ms), 向量精排 (耗时" + vectorCost + "ms), 0.65阈值后有效=" + highConfCandidates.size() + "片, 最终Top=" + topResults.size() + "片, 总耗时: " + totalCost + "ms");

        return new SearchResult(topResults, allMeta.size(), candidateChunks.size(), noiseFiltered, highConfCandidates.size(), totalCost, isRealVector, isRealVector ? "HYBRID_COSINE" : "BM25_MATCH");
    }

    /**
     * 余弦相似度计算 (严格校验维度一致性，维度不匹配返回 0.0，绝不截断计算伪相似度)
     */
    private double computeCosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.length == 0)
            return 0.0;
        if (v1.length != v2.length) {
            System.err.println("[RAG] 向量维度不匹配: query=" + v1.length + ", chunk=" + v2.length + "，放弃计算余弦");
            return 0.0;
        }

        double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        if (norm1 <= 1e-9 || norm2 <= 1e-9)
            return 0.0;
        double cosine = dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
        return Math.max(0.0, Math.min(1.0, cosine));
    }

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank())
            return Collections.emptyList();
        List<String> tokens = new ArrayList<>();

        Pattern enPattern = Pattern.compile("[a-zA-Z0-9_#-]+");
        Matcher enMatcher = enPattern.matcher(text);
        while (enMatcher.find()) {
            String w = enMatcher.group().toLowerCase().trim();
            if (w.length() >= 2)
                tokens.add(w);
        }

        Pattern cnPattern = Pattern.compile("[\\u4e00-\\u9fa5]+");
        Matcher cnMatcher = cnPattern.matcher(text);
        while (cnMatcher.find()) {
            String cn = cnMatcher.group();
            for (int i = 0; i < cn.length(); i++) {
                tokens.add(String.valueOf(cn.charAt(i)));
                if (i + 1 < cn.length())
                    tokens.add(cn.substring(i, i + 2));
                if (i + 2 < cn.length())
                    tokens.add(cn.substring(i, i + 3));
            }
        }
        return tokens;
    }

    public static class OkapiBM25Engine {
        private final double k1;
        private final double b;
        private final double avgDocLength;
        private final Map<String, Integer> docLengths = new HashMap<>();
        private final Map<String, Map<String, Integer>> docTermFreqs = new HashMap<>();
        private final Map<String, Integer> docFreq = new HashMap<>();
        private final int totalDocs;
        private final HybridRagService rag;

        public OkapiBM25Engine(List<com.ownsudo.dto.ChunkMetadata> chunks, double k1, double b, HybridRagService rag) {
            this.k1 = k1;
            this.b = b;
            this.rag = rag;
            this.totalDocs = chunks.size();

            long totalLen = 0;
            for (com.ownsudo.dto.ChunkMetadata c : chunks) {
                List<String> tokens = rag.tokenize(c.getContent());
                int len = tokens.size();
                docLengths.put(c.getId(), len);
                totalLen += len;

                Map<String, Integer> tf = new HashMap<>();
                Set<String> uniqueTokens = new HashSet<>(tokens);
                for (String t : tokens) {
                    tf.put(t, tf.getOrDefault(t, 0) + 1);
                }
                docTermFreqs.put(c.getId(), tf);

                for (String ut : uniqueTokens) {
                    docFreq.put(ut, docFreq.getOrDefault(ut, 0) + 1);
                }
            }
            this.avgDocLength = totalDocs > 0 ? (double) totalLen / totalDocs : 1.0;
        }

        public double computeBM25(String query, String docId) {
            if (query == null || query.isBlank())
                return 0.0;

            // 中文查询词严禁仅按标点空格 split！必须与文档索引使用完全一致的 N-gram/分词引擎！
            List<String> queryTokens = rag.tokenize(query);
            if (queryTokens.isEmpty())
                return 0.0;

            Map<String, Integer> tfMap = docTermFreqs.getOrDefault(docId, Collections.emptyMap());
            int docLen = docLengths.getOrDefault(docId, 1);
            double score = 0.0;

            // 查询词去重打分，避免重复词累加偏离 Okapi 原型
            Set<String> uniqueQueryTokens = new HashSet<>(queryTokens);
            for (String q : uniqueQueryTokens) {
                int tf = tfMap.getOrDefault(q, 0);
                if (tf == 0)
                    continue;

                int df = docFreq.getOrDefault(q, 0);
                double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
                double numerator = tf * (k1 + 1.0);
                double denominator = tf + k1 * (1.0 - b + b * (docLen / avgDocLength));

                score += idf * (numerator / denominator);
            }
            return Math.max(0.0, score);
        }
    }
}
