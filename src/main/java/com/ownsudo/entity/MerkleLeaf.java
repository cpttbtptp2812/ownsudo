package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "merkle_leaf")
public class MerkleLeaf {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "block_height")
    private Long blockHeight;

    @Column(name = "instance_id", length = 64)
    private String instanceId;

    @Column(name = "leaf_index")
    private Integer leafIndex;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    @Column(length = 32)
    private String state;

    @Column(name = "leaf_hash", length = 128)
    private String leafHash;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public MerkleLeaf() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getBlockHeight() { return blockHeight; }
    public void setBlockHeight(Long blockHeight) { this.blockHeight = blockHeight; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public Integer getLeafIndex() { return leafIndex; }
    public void setLeafIndex(Integer leafIndex) { this.leafIndex = leafIndex; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getLeafHash() { return leafHash; }
    public void setLeafHash(String leafHash) { this.leafHash = leafHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}