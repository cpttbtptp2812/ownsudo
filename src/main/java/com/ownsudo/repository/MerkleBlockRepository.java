package com.ownsudo.repository;

import com.ownsudo.entity.MerkleBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerkleBlockRepository extends JpaRepository<MerkleBlock, Long> {
    Optional<MerkleBlock> findFirstByOrderByBlockHeightDesc();
    Optional<MerkleBlock> findByInstanceId(String instanceId);
    List<MerkleBlock> findTop20ByOrderByBlockHeightDesc();
}