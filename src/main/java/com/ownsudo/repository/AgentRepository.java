package com.ownsudo.repository;

import com.ownsudo.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, String> {
    List<AgentEntity> findAllByOrderByCreatedAtDesc();
}
