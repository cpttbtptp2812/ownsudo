package com.ownsudo.controller;

import com.ownsudo.entity.GitRepositoryEntity;
import com.ownsudo.service.GitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
public class GitRepositoryController {

    @Autowired
    private GitService gitService;

    @GetMapping("/repositories")
    public List<GitRepositoryEntity> listRepositories(@RequestParam(required = false) String workflowId) {
        return gitService.getAll(workflowId);
    }

    @GetMapping("/repositories/{id}")
    public ResponseEntity<GitRepositoryEntity> getRepository(@PathVariable String id) {
        return gitService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/repositories")
    public ResponseEntity<GitRepositoryEntity> saveRepository(@RequestBody GitRepositoryEntity entity) {
        GitRepositoryEntity saved = gitService.save(entity);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/repositories/{id}")
    public ResponseEntity<Map<String, Object>> deleteRepository(@PathVariable String id) {
        gitService.delete(id);
        return ResponseEntity.ok(Map.of("ok", true, "deletedId", id));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<GitService.GitProbeResult> testConnection(@RequestBody Map<String, Object> req) {
        String repoUrl = (String) req.get("repoUrl");
        String branch = (String) req.getOrDefault("branch", "main");
        String authType = (String) req.getOrDefault("authType", "none");
        String accessToken = (String) req.get("accessToken");
        String sshKey = (String) req.get("sshKey");

        GitService.GitProbeResult result = gitService.testRemoteConnection(repoUrl, branch, authType, accessToken, sshKey);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/branches")
    public ResponseEntity<GitService.BranchesResult> listBranches(@RequestBody Map<String, Object> req) {
        String repoUrl = (String) req.get("repoUrl");
        String authType = (String) req.getOrDefault("authType", "none");
        String accessToken = (String) req.get("accessToken");
        String sshKey = (String) req.get("sshKey");

        GitService.BranchesResult result = gitService.listRemoteBranches(repoUrl, authType, accessToken, sshKey);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/user-repos")
    public ResponseEntity<List<GitService.RemoteRepoItem>> listUserRepos(@RequestBody Map<String, Object> req) {
        String accessToken = (String) req.get("accessToken");
        String gitHost = (String) req.getOrDefault("gitHost", "github");
        return ResponseEntity.ok(gitService.listUserRepositories(accessToken, gitHost));
    }

    @PostMapping("/verify-token")
    public ResponseEntity<Map<String, Object>> verifyToken(@RequestBody Map<String, Object> req) {
        String accessToken = (String) req.get("accessToken");
        String gitHost = (String) req.getOrDefault("gitHost", "github");
        return ResponseEntity.ok(gitService.verifyToken(accessToken, gitHost));
    }

    @PostMapping("/repositories/{id}/sync")
    public ResponseEntity<?> syncRepository(@PathVariable String id) {
        try {
            GitRepositoryEntity updated = gitService.sync(id);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/oauth/github/callback")
    public ResponseEntity<Map<String, Object>> githubOAuthCallback(@RequestBody Map<String, String> req) {
        String code = req.get("code");
        String clientId = req.get("clientId");
        String clientSecret = req.get("clientSecret");
        return ResponseEntity.ok(gitService.exchangeGithubOAuthToken(code, clientId, clientSecret));
    }

    @GetMapping("/repositories/{id}/commits")
    public ResponseEntity<List<GitService.GitCommitItem>> getCommits(
            @PathVariable String id,
            @RequestParam(defaultValue = "15") int limit
    ) {
        return ResponseEntity.ok(gitService.getCommitHistory(id, limit));
    }

    @GetMapping("/repositories/{id}/diff")
    public ResponseEntity<GitService.GitDiffResult> getDiff(
            @PathVariable String id,
            @RequestParam(required = false) String sha,
            @RequestParam(required = false) String file
    ) {
        return ResponseEntity.ok(gitService.getCommitDiff(id, sha, file));
    }

    @PostMapping("/repositories/{id}/create-fix-branch")
    public ResponseEntity<Map<String, Object>> createFixBranch(
            @PathVariable String id,
            @RequestBody Map<String, String> req
    ) {
        String baseBranch = req.getOrDefault("baseBranch", "main");
        String branchName = req.getOrDefault("branchName", "fix/patch-" + System.currentTimeMillis());
        return ResponseEntity.ok(gitService.createBranch(id, baseBranch, branchName));
    }

}