package com.ownsudo.security;

import com.ownsudo.entity.AgentApiKeyEntity;
import com.ownsudo.repository.AgentApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AgentApiKeyService {

    private static final String KEY_PREFIX = "oa_live_";
    private static final String KEY_SUFFIX_PATTERN = "[0-9a-fA-F]{64}";
    private final AgentApiKeyRepository repository;

    public AgentApiKeyService(AgentApiKeyRepository repository) {
        this.repository = repository;
    }

    public static String hashToken(String token) {
        if (token == null || !token.startsWith(KEY_PREFIX)
                || !token.substring(KEY_PREFIX.length()).matches(KEY_SUFFIX_PATTERN)) {
            throw new IllegalArgumentException("Invalid OwnAgent API key format");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<String> authenticate(String token) {
        if (token == null || token.isBlank() || !token.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        final String hash;
        try {
            hash = hashToken(token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return repository.findByKeyHashAndActiveTrueAndRevokedAtIsNull(hash)
                .map(AgentApiKeyEntity::getAgentId);
    }
}
