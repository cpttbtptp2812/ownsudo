package com.ownsudo.repository;

import com.ownsudo.entity.PromptHashCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromptHashCacheRepository extends JpaRepository<PromptHashCacheEntity, String> {

    @Query("SELECT COALESCE(SUM(c.hitCount), 0) FROM PromptHashCacheEntity c")
    long sumTotalHits();

    @Query("SELECT COALESCE(SUM(c.totalSavedTokens), 0) FROM PromptHashCacheEntity c")
    long sumTotalSavedTokens();

    List<PromptHashCacheEntity> findTop20ByOrderByHitCountDescLastHitAtDesc();
}

