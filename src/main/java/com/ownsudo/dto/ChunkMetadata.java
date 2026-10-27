package com.ownsudo.dto;

public class ChunkMetadata {
    private String id;
    private String docTitle;
    private Integer chunkIndex;
    private String keywords;
    private String content;

    public ChunkMetadata() {}

    public ChunkMetadata(String id, String docTitle, Integer chunkIndex, String keywords, String content) {
        this.id = id;
        this.docTitle = docTitle;
        this.chunkIndex = chunkIndex;
        this.keywords = keywords;
        this.content = content;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}

