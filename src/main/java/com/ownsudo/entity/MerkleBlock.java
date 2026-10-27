package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "merkle_block")
public class MerkleBlock {

    @Id
    @Column(name = "block_height")
    private Long blockHeight;

    @Column(name = "merkle_root", nullable = false, length = 128)
    private String merkleRoot;

    @Column(name = "instance_id", length = 64)
    private String instanceId;

    @Column(name = "previous_hash", length = 128)
    private String previousHash;

    @Column(name = "tx_count")
    private Integer txCount;

    @Column(length = 32)
    private String status; // SEALED, VALIDATED

    @Column(name = "sealed_at")
    private LocalDateTime sealedAt;

    @PrePersist
    public void prePersist() {
        if (sealedAt == null) sealedAt = LocalDateTime.now();
    }

    public MerkleBlock() {}

    public Long getBlockHeight() { return blockHeight; }
    public void setBlockHeight(Long blockHeight) { this.blockHeight = blockHeight; }

    public String getMerkleRoot() { return merkleRoot; }
    public void setMerkleRoot(String merkleRoot) { this.merkleRoot = merkleRoot; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }

    public Integer getTxCount() { return txCount; }
    public void setTxCount(Integer txCount) { this.txCount = txCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getSealedAt() { return sealedAt; }
    public void setSealedAt(LocalDateTime sealedAt) { this.sealedAt = sealedAt; }
}