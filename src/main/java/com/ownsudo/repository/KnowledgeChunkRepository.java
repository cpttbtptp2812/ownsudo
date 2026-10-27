package com.ownsudo.repository;

import com.ownsudo.dto.ChunkMetadata;
import com.ownsudo.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, String>, JpaSpecificationExecutor<KnowledgeChunk> {
    List<KnowledgeChunk> findAllByOrderByCreatedAtDesc();
    List<KnowledgeChunk> findTop20ByOrderByCreatedAtDesc();

    @Query("SELECT k FROM KnowledgeChunk k WHERE LOWER(k.content) LIKE LOWER(CONCAT('%', :kw, '%')) OR LOWER(k.keywords) LIKE LOWER(CONCAT('%', :kw, '%'))")
    List<KnowledgeChunk> searchByKeyword(@Param("kw") String kw);

    @Query("SELECT new com.ownsudo.dto.ChunkMetadata(k.id, k.docTitle, k.chunkIndex, k.keywords, k.content) FROM KnowledgeChunk k")
    List<ChunkMetadata> findAllLightweightChunks();
}
