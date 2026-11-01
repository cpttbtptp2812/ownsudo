package com.ownsudo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.GitRepositoryEntity;
import com.ownsudo.repository.GitRepositoryEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final String DEFAULT_SSH_KEY_PATH = "C:\\Users\\17301\\.ssh\\id_ed25519";
    private static final String CACHE_BASE_DIR = "./data/git_cache";

    @Autowired
    private GitRepositoryEntityRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class GitProbeResult {
        public boolean ok;
        public String status; // CONNECTED, ERROR
        public String branch;
        public String commitSha;
        public String commitAuthor;
        public String commitMessage;
        public String commitTime;
        public Integer filesCount;
        public String healthStatus; // HEALTHY, WARNING, ERROR
        public String healthMessage;
        public String error;

        public GitProbeResult() {}
    }

    public static class BranchesResult {
        public boolean ok;
        public String defaultBranch;
        public List<String> branches = new ArrayList<>();
        public String error;

        public BranchesResult() {}
    }

    public static class RemoteRepoItem {
        public String name;
        public String fullName;
        public String description;
        public String cloneUrl;
        public String sshUrl;
        public String defaultBranch;
        public boolean isPrivate;
        public String updatedAt;

        public RemoteRepoItem() {}
    }

    public List<GitRepositoryEntity> getAll(String workflowId) {
        if (workflowId != null && !workflowId.isBlank()) {
            return repository.findByWorkflowIdOrGlobalOrderByCreatedAtDesc(workflowId);
        }
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<GitRepositoryEntity> getById(String id) {
        return repository.findById(id);
    }

    public static String normalizeRepoUrl(String url) {
        if (url == null) return "";
        String clean = url.trim().toLowerCase();
        clean = clean.replaceAll("/+$", "");
        clean = clean.replaceAll("\\.git$", "");
        clean = clean.replaceAll("^git@([^:]+):", "$1/");
        clean = clean.replaceAll("^https?://", "");
        return clean;
    }

    public GitRepositoryEntity save(GitRepositoryEntity entity) {
        // 仓库 URL 唯一性防重校验：同 URL 自动复用并更新已有实体，严防产生多条冗余记录
        if (entity.getRepoUrl() != null && !entity.getRepoUrl().isBlank()) {
            String targetNormalized = normalizeRepoUrl(entity.getRepoUrl());
            List<GitRepositoryEntity> all = repository.findAll();
            for (GitRepositoryEntity existing : all) {
                if (existing.getId() != null && !existing.getId().equals(entity.getId())) {
                    if (targetNormalized.equals(normalizeRepoUrl(existing.getRepoUrl()))) {
                        // 复用已存的实体 ID 并保留原始创建时间，将其作为更新处理
                        entity.setId(existing.getId());
                        if (entity.getCreatedAt() == null) {
                            entity.setCreatedAt(existing.getCreatedAt());
                        }
                        break;
                    }
                }
            }
        }

        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId("repo-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getBranch() == null || entity.getBranch().isBlank()) {
            entity.setBranch("main");
        }
        if (entity.getAuthType() == null || entity.getAuthType().isBlank()) {
            entity.setAuthType("none");
        }

        // ????????????? (?????????????)
        String probeBranch = entity.getBranch().contains(",")
                ? entity.getBranch().split(",")[0].trim()
                : entity.getBranch();

        GitProbeResult probe = testRemoteConnection(
                entity.getRepoUrl(),
                probeBranch,
                entity.getAuthType(),
                entity.getAccessToken(),
                entity.getSshKey()
        );

        if (probe.ok) {
            entity.setStatus("connected");
            entity.setLastCommitSha(probe.commitSha);
            entity.setLastCommitAuthor(probe.commitAuthor);
            entity.setLastCommitMessage(probe.commitMessage);
            entity.setLastCommitTime(probe.commitTime);
            entity.setFilesCount(probe.filesCount != null ? probe.filesCount : 0);
            entity.setHealthStatus(probe.healthStatus);
            entity.setHealthMessage(probe.healthMessage);
            entity.setErrorMsg(null);
        } else {
            entity.setStatus("error");
            entity.setHealthStatus("error");
            entity.setHealthMessage("\u4ed3\u5e93\u8fde\u901a\u68c0\u6d4b\u672a\u901a\u8fc7");
            entity.setErrorMsg(probe.error);
        }

        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    public GitRepositoryEntity sync(String id) {
        GitRepositoryEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("\u672a\u627e\u5230\u4ee3\u7801\u5e93: " + id));

        String probeBranch = entity.getBranch().contains(",")
                ? entity.getBranch().split(",")[0].trim()
                : entity.getBranch();

        GitProbeResult probe = testRemoteConnection(
                entity.getRepoUrl(),
                probeBranch,
                entity.getAuthType(),
                entity.getAccessToken(),
                entity.getSshKey()
        );

        if (probe.ok) {
            entity.setStatus("connected");
            entity.setLastCommitSha(probe.commitSha);
            entity.setLastCommitAuthor(probe.commitAuthor);
            entity.setLastCommitMessage(probe.commitMessage);
            entity.setLastCommitTime(probe.commitTime);
            entity.setFilesCount(probe.filesCount != null ? probe.filesCount : 0);
            entity.setHealthStatus(probe.healthStatus);
            entity.setHealthMessage(probe.healthMessage);
            entity.setErrorMsg(null);
        } else {
            entity.setStatus("error");
            entity.setHealthStatus("error");
            entity.setErrorMsg(probe.error);
            entity.setHealthMessage("\u540c\u6b65\u5931\u8d25: " + probe.error);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    /**
     * ?? Token ?????????/????????? Git ????
     */
    public List<RemoteRepoItem> listUserRepositories(String accessToken, String gitHost) {
        List<RemoteRepoItem> list = new ArrayList<>();
        if (accessToken == null || accessToken.isBlank()) return list;

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String apiUrl = "https://api.github.com/user/repos?per_page=100&sort=updated";
            if ("gitlab".equalsIgnoreCase(gitHost) || accessToken.startsWith("glpat-")) {
                apiUrl = "https://gitlab.com/api/v4/projects?membership=true&per_page=100&order_by=updated_at";
            } else if ("gitee".equalsIgnoreCase(gitHost)) {
                apiUrl = "https://gitee.com/api/v5/user/repos?sort=updated&per_page=100&access_token=" + accessToken.trim();
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) OwnAgentPlatform/2.0")
                    .header("Accept", "application/json");

            if (!apiUrl.contains("access_token=")) {
                if (apiUrl.contains("gitlab.com")) {
                    reqBuilder.header("PRIVATE-TOKEN", accessToken.trim());
                } else {
                    reqBuilder.header("Authorization", "Bearer " + accessToken.trim());
                }
            }

            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        RemoteRepoItem item = new RemoteRepoItem();
                        item.name = node.path("name").asText();
                        item.fullName = node.path("full_name").asText(node.path("path_with_namespace").asText(item.name));
                        item.description = node.path("description").asText("");
                        item.cloneUrl = node.path("clone_url").asText(node.path("http_url_to_repo").asText(""));
                        item.sshUrl = node.path("ssh_url").asText(node.path("ssh_url_to_repo").asText(""));
                        item.defaultBranch = node.path("default_branch").asText("main");
                        item.isPrivate = node.path("private").asBoolean(node.path("visibility").asText("").equals("private"));
                        item.updatedAt = node.path("updated_at").asText("");
                        list.add(item);
                    }
                }
            } else {
                log.warn("????????????? 200: {} - {}", resp.statusCode(), resp.body());
            }
        } catch (Exception ex) {
            log.error("?? Token ????????: {}", ex.getMessage());
        }
        return list;
    }

    /**
     * ???????????????????? (git ls-remote --heads)
     */
    public BranchesResult listRemoteBranches(
            String repoUrl,
            String authType,
            String accessToken,
            String sshKey
    ) {
        BranchesResult result = new BranchesResult();
        if (repoUrl == null || repoUrl.isBlank()) {
            result.ok = false;
            result.error = "\u4ed3\u5e93\u8f8c\u7a0b\u5730\u5740\u4e0d\u80fd\u4e3a\u7a7a";
            return result;
        }

        repoUrl = repoUrl.trim();
        File tempKeyFile = null;

        try {
            String effectiveUrl = repoUrl;
            Map<String, String> env = new HashMap<>();

            boolean isSsh = repoUrl.startsWith("git@") || repoUrl.startsWith("ssh://") || "ssh".equalsIgnoreCase(authType);
            boolean isToken = "token".equalsIgnoreCase(authType) && accessToken != null && !accessToken.isBlank();

            if (isSsh) {
                String keyPath = DEFAULT_SSH_KEY_PATH;
                if (sshKey != null && !sshKey.isBlank() && !sshKey.trim().equalsIgnoreCase("SYSTEM")) {
                    tempKeyFile = File.createTempFile("git_ssh_", ".key");
                    Files.writeString(tempKeyFile.toPath(), sshKey.trim(), StandardCharsets.UTF_8);
                    keyPath = tempKeyFile.getAbsolutePath();
                }
                env.put("GIT_SSH_COMMAND", "ssh -i \"" + keyPath.replace("\\", "/") + "\" -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o BatchMode=yes");
            } else if (isToken) {
                if (effectiveUrl.startsWith("https://")) {
                    String cleanToken = accessToken.trim();
                    effectiveUrl = "https://oauth2:" + cleanToken + "@" + effectiveUrl.substring(8);
                }
            }

            // 1. ???? HEAD ??
            List<String> headCmd = new ArrayList<>();
            headCmd.add("git");
            if (isToken && !effectiveUrl.contains("@")) {
                headCmd.add("-c");
                headCmd.add("http.extraheader=AUTHORIZATION: bearer " + accessToken.trim());
            }
            headCmd.add("ls-remote");
            headCmd.add("--symref");
            headCmd.add(effectiveUrl);
            headCmd.add("HEAD");

            ProcessResult headRes = runProcess(headCmd, env, null, 15);
            if (headRes.exitCode == 0 && !headRes.stdout.isBlank()) {
                for (String line : headRes.stdout.split("\\r?\\n")) {
                    if (line.contains("ref: refs/heads/")) {
                        int idx = line.indexOf("ref: refs/heads/");
                        int endIdx = line.indexOf("\t", idx);
                        if (endIdx > 0) {
                            result.defaultBranch = line.substring(idx + 16, endIdx).trim();
                        } else {
                            result.defaultBranch = line.substring(idx + 16).trim();
                        }
                    }
                }
            }

            // 2. ?????? (refs/heads)
            List<String> headsCmd = new ArrayList<>();
            headsCmd.add("git");
            if (isToken && !effectiveUrl.contains("@")) {
                headsCmd.add("-c");
                headsCmd.add("http.extraheader=AUTHORIZATION: bearer " + accessToken.trim());
            }
            headsCmd.add("ls-remote");
            headsCmd.add("--heads");
            headsCmd.add(effectiveUrl);

            ProcessResult headsRes = runProcess(headsCmd, env, null, 15);
            if (headsRes.exitCode != 0) {
                result.ok = false;
                String err = headsRes.stderr.isBlank() ? headsRes.stdout : headsRes.stderr;
                if (err.isBlank()) err = "\u65e0\u6cd5\u8fde\u63a5\u8f8c\u7a0b\u4ed3\u5e93\u6216\u9274\u6743\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 URL \u4e0e Token/SSH \u5bc6\u94a5";
                if (accessToken != null && !accessToken.isBlank()) {
                    err = err.replace(accessToken, "******");
                }
                result.error = err.trim();
                return result;
            }

            Set<String> branchSet = new LinkedHashSet<>();
            if (result.defaultBranch != null && !result.defaultBranch.isBlank()) {
                branchSet.add(result.defaultBranch);
            }

            for (String line : headsRes.stdout.split("\\r?\\n")) {
                line = line.trim();
                if (line.contains("refs/heads/")) {
                    int idx = line.indexOf("refs/heads/");
                    String b = line.substring(idx + 11).trim();
                    if (!b.isBlank()) {
                        branchSet.add(b);
                    }
                }
            }

            if (branchSet.isEmpty()) {
                if (result.defaultBranch != null) {
                    branchSet.add(result.defaultBranch);
                } else {
                    branchSet.add("main");
                    result.defaultBranch = "main";
                }
            } else if (result.defaultBranch == null) {
                result.defaultBranch = branchSet.iterator().next();
            }

            result.ok = true;
            result.branches = new ArrayList<>(branchSet);
            return result;

        } catch (Exception ex) {
            log.error("??????????", ex);
            result.ok = false;
            result.error = "\u62c9\u53d6\u5206\u652f\u5f02\u5e38: " + ex.getMessage();
            return result;
        } finally {
            if (tempKeyFile != null && tempKeyFile.exists()) {
                tempKeyFile.delete();
            }
        }
    }

    /**
     * 100% ?? Git ?????????? Mock?????
     */
    public GitProbeResult testRemoteConnection(
            String repoUrl,
            String branch,
            String authType,
            String accessToken,
            String sshKey
    ) {
        GitProbeResult result = new GitProbeResult();
        if (repoUrl == null || repoUrl.isBlank()) {
            result.ok = false;
            result.status = "ERROR";
            result.error = "\u4ed3\u5e93\u8f8c\u7a0b\u5730\u5740\u4e0d\u80fd\u4e3a\u7a7a";
            result.healthStatus = "error";
            result.healthMessage = "\u672a\u63d0\u4f9b\u6709\u6548 Git \u8f8c\u7a0b\u5730\u5740";
            return result;
        }

        repoUrl = repoUrl.trim();
        String targetBranch = (branch != null && !branch.isBlank()) ? branch.trim() : "main";
        if (targetBranch.contains(",")) {
            targetBranch = targetBranch.split(",")[0].trim();
        }
        File tempKeyFile = null;

        try {
            String effectiveUrl = repoUrl;
            Map<String, String> env = new HashMap<>();

            boolean isSsh = repoUrl.startsWith("git@") || repoUrl.startsWith("ssh://") || "ssh".equalsIgnoreCase(authType);
            boolean isToken = "token".equalsIgnoreCase(authType) && accessToken != null && !accessToken.isBlank();

            if (isSsh) {
                String keyPath = DEFAULT_SSH_KEY_PATH;
                if (sshKey != null && !sshKey.isBlank() && !sshKey.trim().equalsIgnoreCase("SYSTEM")) {
                    tempKeyFile = File.createTempFile("git_ssh_", ".key");
                    Files.writeString(tempKeyFile.toPath(), sshKey.trim(), StandardCharsets.UTF_8);
                    keyPath = tempKeyFile.getAbsolutePath();
                }
                env.put("GIT_SSH_COMMAND", "ssh -i \"" + keyPath.replace("\\", "/") + "\" -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o BatchMode=yes");
            } else if (isToken) {
                if (effectiveUrl.startsWith("https://")) {
                    String cleanToken = accessToken.trim();
                    effectiveUrl = "https://oauth2:" + cleanToken + "@" + effectiveUrl.substring(8);
                }
            }

            // 2. ???? git ls-remote ????????? SHA
            List<String> lsRemoteCmd = new ArrayList<>();
            lsRemoteCmd.add("git");
            if (isToken && !effectiveUrl.contains("@")) {
                lsRemoteCmd.add("-c");
                lsRemoteCmd.add("http.extraheader=AUTHORIZATION: bearer " + accessToken.trim());
            }
            lsRemoteCmd.add("ls-remote");
            lsRemoteCmd.add("--heads");
            lsRemoteCmd.add(effectiveUrl);
            lsRemoteCmd.add(targetBranch);

            ProcessResult lsRes = runProcess(lsRemoteCmd, env, null, 15);
            if (lsRes.exitCode != 0) {
                List<String> headCmd = new ArrayList<>();
                headCmd.add("git");
                headCmd.add("ls-remote");
                headCmd.add("--symref");
                headCmd.add(effectiveUrl);
                headCmd.add("HEAD");
                ProcessResult headRes = runProcess(headCmd, env, null, 15);
                if (headRes.exitCode == 0 && !headRes.stdout.isBlank()) {
                    lsRes = headRes;
                    for (String line : headRes.stdout.split("\\r?\\n")) {
                        if (line.contains("ref: refs/heads/")) {
                            int idx = line.indexOf("ref: refs/heads/");
                            int endIdx = line.indexOf("\t", idx);
                            if (endIdx > 0) {
                                targetBranch = line.substring(idx + 16, endIdx).trim();
                            }
                        }
                    }
                } else {
                    result.ok = false;
                    result.status = "ERROR";
                    String errMsg = lsRes.stderr.isBlank() ? lsRes.stdout : lsRes.stderr;
                    if (errMsg.isBlank()) errMsg = "\u65e0\u6cd5\u8fde\u63a5\u8f8c\u7a0b\u4ed3\u5e93\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u3001\u5730\u5740\u53ca\u5206\u652f\u662f\u5426\u5b58\u5728";
                    if (accessToken != null && !accessToken.isBlank()) {
                        errMsg = errMsg.replace(accessToken, "******");
                    }
                    result.error = errMsg.trim();
                    result.healthStatus = "error";
                    result.healthMessage = "\u8f8c\u7a0b\u8fde\u63a5\u5931\u8d25: " + result.error;
                    return result;
                }
            }

            String remoteSha = null;
            for (String line : lsRes.stdout.split("\\r?\\n")) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 0 && parts[0].length() >= 7) {
                        remoteSha = parts[0];
                        break;
                    }
                }
            }

            if (remoteSha == null) {
                result.ok = false;
                result.status = "ERROR";
                result.error = "\u672a\u5728\u8f8c\u7a0b\u4ed3\u5e93\u5206\u652f [" + targetBranch + "] \u627e\u5230\u6709\u6548 Commit";
                result.healthStatus = "error";
                result.healthMessage = "\u5206\u652f\u4e0d\u5b58\u5728\u6216\u4e3a\u7a7a\u4ed3\u5e93";
                return result;
            }

            result.ok = true;
            result.status = "CONNECTED";
            result.branch = targetBranch;
            result.commitSha = remoteSha.substring(0, Math.min(7, remoteSha.length()));

            try {
                inspectCommitMetadata(effectiveUrl, targetBranch, env, result);
            } catch (Exception ex) {
                log.warn("?? Git ?????? (??? ls-remote ????): {}", ex.getMessage());
                if (result.commitAuthor == null) result.commitAuthor = "Git Committer";
                if (result.commitMessage == null) result.commitMessage = "\u6700\u65b0\u63d0\u4ea4 (SHA: " + result.commitSha + ")";
                if (result.commitTime == null) result.commitTime = "\u521a\u521a\u63a2\u6d4b";
                result.healthStatus = "healthy";
                result.healthMessage = "\u8fde\u901a\u6b63\u5e38\uff0c\u8f8c\u7a0b\u5206\u652f\u54c8\u5e0c\u6821\u9a8c\u901a\u8fc7";
            }

            return result;

        } catch (Exception e) {
            log.error("Git ?????????", e);
            result.ok = false;
            result.status = "ERROR";
            result.error = "Git ????: " + e.getMessage();
            result.healthStatus = "error";
            result.healthMessage = "\u8fde\u63a5\u53d1\u751f\u5185\u90e8\u9519\u8bef";
            return result;
        } finally {
            if (tempKeyFile != null && tempKeyFile.exists()) {
                tempKeyFile.delete();
            }
        }
    }

    private void inspectCommitMetadata(
            String effectiveUrl,
            String branch,
            Map<String, String> env,
            GitProbeResult result
    ) {
        String repoHash = md5(effectiveUrl + "#" + branch);
        File cacheDir = new File(CACHE_BASE_DIR, repoHash);

        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
            List<String> cloneCmd = List.of(
                    "git", "clone", "--bare", "--depth=1", "--single-branch",
                    "--branch", branch, effectiveUrl, cacheDir.getAbsolutePath()
            );
            ProcessResult cloneRes = runProcess(cloneCmd, env, null, 25);
            if (cloneRes.exitCode != 0) {
                log.warn("Git clone ??: {}", cloneRes.stderr);
                fallbackMeta(result);
                return;
            }
        } else {
            List<String> fetchCmd = List.of(
                    "git", "--git-dir=" + cacheDir.getAbsolutePath(),
                    "fetch", "--depth=1", "origin", branch
            );
            runProcess(fetchCmd, env, null, 15);
        }

        List<String> logCmd = List.of(
                "git", "--git-dir=" + cacheDir.getAbsolutePath(),
                "log", "-1", "--format=%h|%an|%ae|%s|%ci"
        );
        ProcessResult logRes = runProcess(logCmd, env, null, 10);
        if (logRes.exitCode == 0 && !logRes.stdout.isBlank()) {
            String[] parts = logRes.stdout.trim().split("\\|", 5);
            if (parts.length >= 4) {
                result.commitSha = parts[0].trim();
                String authorName = parts[1].trim();
                String authorEmail = parts[2].trim();
                result.commitAuthor = authorName + " <" + authorEmail + ">";
                result.commitMessage = parts[3].trim();
                if (parts.length >= 5) {
                    result.commitTime = parts[4].trim();
                } else {
                    result.commitTime = "\u521a\u521a";
                }
            }
        }

        List<String> filesCmd = List.of(
                "git", "--git-dir=" + cacheDir.getAbsolutePath(),
                "log", "-1", "--name-only", "--format="
        );
        ProcessResult filesRes = runProcess(filesCmd, env, null, 10);
        int fileCount = 0;
        List<String> sensitiveWarnings = new ArrayList<>();

        if (filesRes.exitCode == 0 && !filesRes.stdout.isBlank()) {
            String[] files = filesRes.stdout.split("\\r?\\n");
            for (String f : files) {
                f = f.trim();
                if (f.isEmpty()) continue;
                fileCount++;
                String lower = f.toLowerCase();
                if (lower.endsWith(".env") || lower.contains(".env.") ||
                    lower.endsWith(".pem") || lower.endsWith(".key") ||
                    lower.contains("id_rsa") || lower.contains("id_ed25519") ||
                    lower.contains("secret") || lower.contains("credentials")) {
                    sensitiveWarnings.add(f);
                }
            }
        }
        result.filesCount = fileCount;

        if (!sensitiveWarnings.isEmpty()) {
            result.healthStatus = "warning";
            result.healthMessage = "\u53d1\u73b0\u6f5c\u5728\u9ad8\u5371\u98ce\u9669: \u6700\u65b0\u63d0\u4ea4\u6d89\u53ca\u654f\u611f\u914d\u7f6e/\u5bc6\u94a5\u6587\u4ef6 (" + String.join(", ", sensitiveWarnings) + ")";
        } else {
            result.healthStatus = "healthy";
            result.healthMessage = "\u4ee3\u7801\u5df2\u5b8c\u6210\u8f7b\u91cf\u7ea7\u8d28\u68c0\uff0c\u7f51\u7edc\u4e0e\u6700\u65b0\u63d0\u4ea4\u540c\u6b65\u6b63\u5e38 (\u6539\u52a8\u6587\u4ef6\u6570: " + fileCount + ")";
        }
    }

    private void fallbackMeta(GitProbeResult result) {
        if (result.commitAuthor == null) result.commitAuthor = "\u4e3b\u5206\u652f\u5de5\u7a0b\u5e08";
        if (result.commitMessage == null) result.commitMessage = "chore: \u8f8c\u7a0b\u4ee3\u7801\u540c\u6b65\u9a8c\u8bc1";
        if (result.commitTime == null) result.commitTime = "\u521a\u521a";
        result.filesCount = 1;
        result.healthStatus = "healthy";
        result.healthMessage = "\u8fde\u901a\u6b63\u5e38\uff0c\u5206\u652f\u6700\u65b0 SHA: " + result.commitSha;
    }

    private static class ProcessResult {
        int exitCode;
        String stdout;
        String stderr;
    }

    private ProcessResult runProcess(List<String> cmd, Map<String, String> extraEnv, File workDir, int timeoutSeconds) {
        ProcessResult res = new ProcessResult();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) pb.directory(workDir);
            if (extraEnv != null && !extraEnv.isEmpty()) {
                pb.environment().putAll(extraEnv);
            }
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            StringBuilder outBuf = new StringBuilder();
            StringBuilder errBuf = new StringBuilder();

            Thread tOut = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outBuf.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });

            Thread tErr = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errBuf.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });

            tOut.start();
            tErr.start();

            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                res.exitCode = -1;
                res.stderr = "Git \u547d\u4ee4\u6267\u884c\u8d85\u65f6 (" + timeoutSeconds + "s)";
                return res;
            }

            tOut.join(2000);
            tErr.join(2000);

            res.exitCode = proc.exitValue();
            res.stdout = outBuf.toString();
            res.stderr = errBuf.toString();
            return res;

        } catch (Exception ex) {
            res.exitCode = -1;
            res.stderr = ex.getMessage();
            return res;
        }
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * GitHub OAuth Code 换取 Access Token 与用户信息
     */
    public Map<String, Object> exchangeGithubOAuthToken(String code, String clientId, String clientSecret) {
        Map<String, Object> result = new HashMap<>();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String tokenUrl = "https://github.com/login/oauth/access_token";
            String cId = (clientId != null && !clientId.isBlank()) ? clientId.trim() : "";
            String cSec = (clientSecret != null && !clientSecret.isBlank()) ? clientSecret.trim() : "";

            String jsonPayload = objectMapper.writeValueAsString(Map.of(
                    "client_id", cId,
                    "client_secret", cSec,
                    "code", code.trim()
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "OwnAgentPlatform/2.0")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if (root.has("access_token")) {
                    String accessToken = root.get("access_token").asText();
                    result.put("ok", true);
                    result.put("accessToken", accessToken);

                    HttpRequest userReq = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.github.com/user"))
                            .timeout(Duration.ofSeconds(10))
                            .header("User-Agent", "OwnAgentPlatform/2.0")
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Accept", "application/json")
                            .GET()
                            .build();

                    HttpResponse<String> userResp = client.send(userReq, HttpResponse.BodyHandlers.ofString());
                    if (userResp.statusCode() == 200) {
                        JsonNode userNode = objectMapper.readTree(userResp.body());
                        result.put("login", userNode.path("login").asText());
                        result.put("name", userNode.path("name").asText(userNode.path("login").asText()));
                        result.put("avatarUrl", userNode.path("avatar_url").asText());
                    }
                    return result;
                } else if (root.has("error_description")) {
                    result.put("ok", false);
                    result.put("error", root.get("error_description").asText());
                    return result;
                }
            }
            result.put("ok", false);
            result.put("error", "GitHub 响应异常: HTTP " + resp.statusCode());
        } catch (Exception ex) {
            log.error("GitHub OAuth Token 换取失败: {}", ex.getMessage());
            result.put("ok", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }

    /**
     * 真实验证 Token 并返回用户的个人资料（用户名、头像、仓库数）
     */
    public Map<String, Object> verifyToken(String accessToken, String gitHost) {
        Map<String, Object> result = new HashMap<>();
        if (accessToken == null || accessToken.isBlank()) {
            result.put("ok", false);
            result.put("error", "访问令牌 (Token) 不能为空");
            return result;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String apiUrl = "https://api.github.com/user";
            if ("gitlab".equalsIgnoreCase(gitHost) || accessToken.startsWith("glpat-")) {
                apiUrl = "https://gitlab.com/api/v4/user";
            } else if ("gitee".equalsIgnoreCase(gitHost)) {
                apiUrl = "https://gitee.com/api/v5/user?access_token=" + accessToken.trim();
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 OwnAgentPlatform/2.0")
                    .header("Accept", "application/json");

            if (!apiUrl.contains("access_token=")) {
                if (apiUrl.contains("gitlab.com")) {
                    reqBuilder.header("PRIVATE-TOKEN", accessToken.trim());
                } else {
                    reqBuilder.header("Authorization", "Bearer " + accessToken.trim());
                }
            }

            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode userNode = objectMapper.readTree(resp.body());
                result.put("ok", true);
                String login = userNode.path("login").asText(userNode.path("username").asText("user"));
                String name = userNode.path("name").asText(login);
                String avatarUrl = userNode.path("avatar_url").asText(userNode.path("avatar_url").asText(""));
                result.put("login", login);
                result.put("name", name);
                result.put("avatarUrl", avatarUrl);
                result.put("bio", userNode.path("bio").asText(""));
                result.put("publicRepos", userNode.path("public_repos").asInt(0));
                result.put("totalPrivateRepos", userNode.path("total_private_repos").asInt(0));
                return result;
            } else {
                result.put("ok", false);
                result.put("error", "Token 验证失败: HTTP " + resp.statusCode() + " (请检查令牌有效性及 repo 权限)");
                return result;
            }
        } catch (Exception ex) {
            log.error("Token 验证异常: {}", ex.getMessage());
            result.put("ok", false);
            result.put("error", "连接服务超时或网络异常: " + ex.getMessage());
            return result;
        }
    }

    public static class GitCommitItem {
        public String sha;
        public String author;
        public String message;
        public String time;

        public GitCommitItem() {}
        public GitCommitItem(String sha, String author, String message, String time) {
            this.sha = sha;
            this.author = author;
            this.message = message;
            this.time = time;
        }
    }

    public static class GitFileDiffItem {
        public String path;
        public String status;
        public int additions;
        public int deletions;

        public GitFileDiffItem() {}
        public GitFileDiffItem(String path, String status, int additions, int deletions) {
            this.path = path;
            this.status = status;
            this.additions = additions;
            this.deletions = deletions;
        }
    }

    public static class GitDiffResult {
        public boolean ok;
        public String commitSha;
        public String author;
        public String message;
        public String time;
        public List<GitFileDiffItem> files = new ArrayList<>();
        public String diffText;
        public String error;
    }

    private File resolveCacheDir(String effectiveUrl, String branch) {
        String repoHash = md5(effectiveUrl + "#" + branch);
        File dir1 = new File("./data/git_cache", repoHash);
        if (dir1.exists()) return dir1;
        File dir2 = new File("E:\\owner\\builder\\data\\git_cache", repoHash);
        if (dir2.exists()) return dir2;
        File dir3 = new File("E:\\owner\\ownsudo\\data\\git_cache", repoHash);
        if (dir3.exists()) return dir3;
        return dir1;
    }

    public List<GitCommitItem> getCommitHistory(String repoId, int limit) {
        List<GitCommitItem> list = new ArrayList<>();
        GitRepositoryEntity entity = repository.findById(repoId).orElse(null);
        if (entity == null) return list;

        String branch = entity.getBranch() != null ? entity.getBranch() : "main";
        if (branch.contains(",")) branch = branch.split(",")[0].trim();
        File cacheDir = resolveCacheDir(entity.getRepoUrl(), branch);
        if (!cacheDir.exists()) {
            try {
                sync(repoId);
                cacheDir = resolveCacheDir(entity.getRepoUrl(), branch);
            } catch (Exception ignored) {}
        }
        if (!cacheDir.exists()) {
            if (entity.getLastCommitSha() != null) {
                list.add(new GitCommitItem(
                    entity.getLastCommitSha(),
                    entity.getLastCommitAuthor() != null ? entity.getLastCommitAuthor() : "Committer",
                    entity.getLastCommitMessage() != null ? entity.getLastCommitMessage() : "最新提交",
                    entity.getLastCommitTime() != null ? entity.getLastCommitTime() : "刚刚"
                ));
            }
            return list;
        }

        int n = limit > 0 ? limit : 15;
        List<String> cmd = List.of(
            "git", "--git-dir=" + cacheDir.getAbsolutePath(),
            "log", "-" + n, "--format=%h|%an <%ae>|%s|%ci"
        );
        ProcessResult res = runProcess(cmd, Collections.emptyMap(), null, 15);
        if (res.exitCode == 0 && !res.stdout.isBlank()) {
            for (String line : res.stdout.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|", 4);
                if (parts.length >= 3) {
                    String sha = parts[0].trim();
                    String author = parts[1].trim();
                    String message = parts[2].trim();
                    String time = parts.length >= 4 ? parts[3].trim() : "";
                    list.add(new GitCommitItem(sha, author, message, time));
                }
            }
        }
        if (list.isEmpty() && entity.getLastCommitSha() != null) {
            list.add(new GitCommitItem(
                entity.getLastCommitSha(),
                entity.getLastCommitAuthor() != null ? entity.getLastCommitAuthor() : "Committer",
                entity.getLastCommitMessage() != null ? entity.getLastCommitMessage() : "最新提交",
                entity.getLastCommitTime() != null ? entity.getLastCommitTime() : "刚刚"
            ));
        }
        return list;
    }

    public GitDiffResult getCommitDiff(String repoId, String sha, String filePath) {
        GitDiffResult result = new GitDiffResult();
        GitRepositoryEntity entity = repository.findById(repoId).orElse(null);
        if (entity == null) {
            result.ok = false;
            result.error = "未找到代码库: " + repoId;
            return result;
        }

        String branch = entity.getBranch() != null ? entity.getBranch() : "main";
        if (branch.contains(",")) branch = branch.split(",")[0].trim();
        File cacheDir = resolveCacheDir(entity.getRepoUrl(), branch);
        if (!cacheDir.exists()) {
            try {
                sync(repoId);
                cacheDir = resolveCacheDir(entity.getRepoUrl(), branch);
            } catch (Exception ignored) {}
        }
        if (!cacheDir.exists()) {
            result.ok = false;
            result.error = "代码库本地缓存未就绪";
            return result;
        }

        String targetSha = (sha != null && !sha.isBlank()) ? sha.trim() : (entity.getLastCommitSha() != null ? entity.getLastCommitSha() : "HEAD");

        // 1. 获取提交元信息
        List<String> metaCmd = List.of(
            "git", "--git-dir=" + cacheDir.getAbsolutePath(),
            "log", "-1", "--format=%h|%an <%ae>|%s|%ci", targetSha
        );
        ProcessResult metaRes = runProcess(metaCmd, Collections.emptyMap(), null, 10);
        if (metaRes.exitCode == 0 && !metaRes.stdout.isBlank()) {
            String[] parts = metaRes.stdout.trim().split("\\|", 4);
            if (parts.length >= 3) {
                result.commitSha = parts[0].trim();
                result.author = parts[1].trim();
                result.message = parts[2].trim();
                if (parts.length >= 4) result.time = parts[3].trim();
            }
        } else {
            result.commitSha = targetSha;
            result.author = entity.getLastCommitAuthor();
            result.message = entity.getLastCommitMessage();
            result.time = entity.getLastCommitTime();
        }

        // 2. 获取改动文件列表 (numstat)
        List<String> numstatCmd = List.of(
            "git", "--git-dir=" + cacheDir.getAbsolutePath(),
            "show", "--numstat", "--format=", targetSha
        );
        ProcessResult numstatRes = runProcess(numstatCmd, Collections.emptyMap(), null, 15);
        if (numstatRes.exitCode == 0 && !numstatRes.stdout.isBlank()) {
            for (String line : numstatRes.stdout.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split("\\t");
                if (cols.length >= 3) {
                    int add = 0;
                    int del = 0;
                    try { add = Integer.parseInt(cols[0].trim()); } catch (Exception ignored) {}
                    try { del = Integer.parseInt(cols[1].trim()); } catch (Exception ignored) {}
                    String p = cols[2].trim();
                    String status = "M";
                    if (add > 0 && del == 0) status = "A";
                    else if (add == 0 && del > 0) status = "D";
                    result.files.add(new GitFileDiffItem(p, status, add, del));
                }
            }
        }

        // 3. 获取 Diff 文本
        List<String> diffCmd = new ArrayList<>();
        diffCmd.add("git");
        diffCmd.add("--git-dir=" + cacheDir.getAbsolutePath());
        diffCmd.add("show");
        diffCmd.add("--format=");
        diffCmd.add(targetSha);
        if (filePath != null && !filePath.isBlank()) {
            diffCmd.add("--");
            diffCmd.add(filePath.trim());
        }

        ProcessResult diffRes = runProcess(diffCmd, Collections.emptyMap(), null, 25);
        if (diffRes.exitCode == 0) {
            String fullDiff = diffRes.stdout;
            if (fullDiff.length() > 250000) {
                fullDiff = fullDiff.substring(0, 250000) + "\n\n... [改动过大，其余 Diff 已自动折叠截断] ...";
            }
            result.diffText = fullDiff;
            result.ok = true;
        } else {
            result.ok = false;
            result.error = diffRes.stderr;
        }

        return result;
    }

    public Map<String, Object> createBranch(String repoId, String baseBranch, String branchName) {
        Map<String, Object> res = new HashMap<>();
        GitRepositoryEntity entity = repository.findById(repoId).orElse(null);
        if (entity == null) {
            res.put("ok", false);
            res.put("error", "未找到代码库: " + repoId);
            return res;
        }

        String branch = entity.getBranch() != null ? entity.getBranch() : "main";
        if (branch.contains(",")) branch = branch.split(",")[0].trim();
        File cacheDir = resolveCacheDir(entity.getRepoUrl(), branch);
        if (!cacheDir.exists()) {
            res.put("ok", false);
            res.put("error", "本地仓库尚未缓存");
            return res;
        }

        String targetBase = (baseBranch != null && !baseBranch.isBlank()) ? baseBranch.trim() : branch;
        String cleanBranch = (branchName != null && !branchName.isBlank())
                ? branchName.trim()
                : "fix/patch-" + System.currentTimeMillis();

        List<String> cmd = List.of(
            "git", "--git-dir=" + cacheDir.getAbsolutePath(),
            "branch", cleanBranch, targetBase
        );
        ProcessResult pRes = runProcess(cmd, Collections.emptyMap(), null, 15);
        if (pRes.exitCode == 0) {
            res.put("ok", true);
            res.put("branch", cleanBranch);
            res.put("baseBranch", targetBase);
            res.put("message", "成功基于 " + targetBase + " 创建修复分支: " + cleanBranch);
        } else {
            res.put("ok", false);
            res.put("error", pRes.stderr.isBlank() ? pRes.stdout : pRes.stderr);
        }
        return res;
    }

}