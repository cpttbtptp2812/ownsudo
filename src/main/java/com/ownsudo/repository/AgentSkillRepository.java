package com.ownsudo.repository;

import com.ownsudo.entity.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkill, String> {
    List<AgentSkill> findByEnabledTrueOrderByCreatedAtDesc();
    List<AgentSkill> findByCategoryAndEnabledTrue(String category);
}