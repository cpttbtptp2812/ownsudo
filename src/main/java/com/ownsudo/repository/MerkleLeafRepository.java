package com.ownsudo.repository;

import com.ownsudo.entity.MerkleLeaf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerkleLeafRepository extends JpaRepository<MerkleLeaf, String> {
    List<MerkleLeaf> findByInstanceIdOrderByLeafIndexAsc(String instanceId);
    List<MerkleLeaf> findByBlockHeightOrderByLeafIndexAsc(Long blockHeight);
}