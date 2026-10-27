package com.ownsudo.service;

import com.ownsudo.entity.MerkleBlock;
import com.ownsudo.entity.MerkleLeaf;
import com.ownsudo.entity.NodeExecution;
import com.ownsudo.repository.MerkleBlockRepository;
import com.ownsudo.repository.MerkleLeafRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MerkleLedgerService {

    @Autowired
    private MerkleBlockRepository blockRepository;

    @Autowired
    private MerkleLeafRepository leafRepository;

    public Map<String, Object> generateMerkleProof(String instanceId, List<NodeExecution> steps) {
        List<Map<String, Object>> leaves = new ArrayList<>();
        List<String> hashes = new ArrayList<>();

        long blockHeight = 10000L + Math.abs(instanceId.hashCode() % 50000);

        for (int i = 0; i < steps.size(); i++) {
            NodeExecution s = steps.get(i);
            String prev = i == 0 ? "GENESIS_HASH" : hashes.get(i - 1);
            String content = prev + ":" + s.getNodeId() + ":" + s.getStatus() + ":" + s.getOutputPayload();
            String hash = sha256(content);
            hashes.add(hash);

            leaves.add(Map.of(
                "leafIndex", i + 1,
                "nodeId", s.getNodeId(),
                "state", s.getStatus() != null ? s.getStatus() : "SUCCESS",
                "hash", hash,
                "intermediateRoot", hash,
                "timestamp", s.getEndedAt() != null ? s.getEndedAt().toString() : LocalDateTime.now().toString()
            ));

            // Persist leaf to DB if not present
            String leafId = "leaf-" + instanceId + "-" + (i + 1);
            if (!leafRepository.existsById(leafId)) {
                MerkleLeaf entity = new MerkleLeaf();
                entity.setId(leafId);
                entity.setBlockHeight(blockHeight);
                entity.setInstanceId(instanceId);
                entity.setLeafIndex(i + 1);
                entity.setNodeId(s.getNodeId());
                entity.setState(s.getStatus() != null ? s.getStatus() : "SUCCESS");
                entity.setLeafHash(hash);
                entity.setCreatedAt(s.getEndedAt() != null ? s.getEndedAt() : LocalDateTime.now());
                leafRepository.save(entity);
            }
        }

        String merkleRoot = calculateMerkleRoot(hashes);

        // Persist block to DB if not present
        if (!blockRepository.existsById(blockHeight)) {
            MerkleBlock block = new MerkleBlock();
            block.setBlockHeight(blockHeight);
            block.setMerkleRoot(merkleRoot);
            block.setInstanceId(instanceId);
            block.setPreviousHash("0x" + sha256("PREV_BLOCK_" + (blockHeight - 1)));
            block.setTxCount(steps.size());
            block.setStatus("SEALED");
            block.setSealedAt(LocalDateTime.now());
            blockRepository.save(block);
        }

        return Map.of(
            "instanceId", instanceId,
            "merkleRoot", merkleRoot,
            "blockHeight", blockHeight,
            "sealedAt", LocalDateTime.now().toString(),
            "leaves", leaves
        );
    }

    private String calculateMerkleRoot(List<String> hashes) {
        if (hashes.isEmpty()) return "0x0000000000000000000000000000000000000000000000000000000000000000";
        List<String> current = new ArrayList<>(hashes);
        while (current.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i += 2) {
                if (i + 1 < current.size()) {
                    next.add(sha256(current.get(i) + current.get(i + 1)));
                } else {
                    next.add(sha256(current.get(i) + current.get(i)));
                }
            }
            current = next;
        }
        return current.get(0);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}