package com.ownsudo.repository;

import com.ownsudo.entity.McpTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface McpToolRepository extends JpaRepository<McpTool, String> {
    List<McpTool> findByEnabledTrue();
    List<McpTool> findByServerNameAndEnabledTrue(String serverName);
}