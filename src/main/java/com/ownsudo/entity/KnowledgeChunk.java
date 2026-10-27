package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "doc_title", length = 128)
    private String docTitle;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(length = 512)
    private String keywords;

    @Column(length = 64)
    private String category;

    @Column(columnDefinition = "LONGTEXT")
    private String embeddingJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public KnowledgeChunk() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}