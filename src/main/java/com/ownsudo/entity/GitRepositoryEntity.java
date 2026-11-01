package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "git_repository")
public class GitRepositoryEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "repo_url", nullable = false, length = 512)
    private String repoUrl;

    @Column(length = 64)
    private String branch = "main";

    @Column(name = "auth_type", length = 32)
    private String authType = "none"; // "token", "ssh", "none"

    @Column(name = "access_token", length = 255)
    private String accessToken;

    @Column(name = "ssh_key", columnDefinition = "LONGTEXT")
    private String sshKey;

    @Column(name = "local_path", length = 512)
    private String localPath;

    @Column(name = "polling_interval_minutes")
    private Integer pollingIntervalMinutes = 5;

    @Column(name = "auto_analyze_commit")
    private Boolean autoAnalyzeCommit = true;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 32)
    private String status = "connected"; // "connected", "disconnected", "connecting", "error"

    @Column(name = "last_commit_sha", length = 64)
    private String lastCommitSha;

    @Column(name = "last_commit_message", columnDefinition = "TEXT")
    private String lastCommitMessage;

    @Column(name = "last_commit_author", length = 128)
    private String lastCommitAuthor;

    @Column(name = "last_commit_time", length = 64)
    private String lastCommitTime;

    @Column(name = "files_count")
    private Integer filesCount = 0;

    @Column(name = "health_status", length = 32)
    private String healthStatus = "healthy"; // "healthy", "warning", "error"

    @Column(name = "health_message", columnDefinition = "TEXT")
    private String healthMessage;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (branch == null || branch.isBlank()) branch = "main";
        if (authType == null || authType.isBlank()) authType = "none";
        if (status == null) status = "connected";
        if (healthStatus == null) healthStatus = "healthy";
        if (enabled == null) enabled = true;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public GitRepositoryEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getSshKey() { return sshKey; }
    public void setSshKey(String sshKey) { this.sshKey = sshKey; }

    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }

    public Integer getPollingIntervalMinutes() { return pollingIntervalMinutes; }
    public void setPollingIntervalMinutes(Integer pollingIntervalMinutes) { this.pollingIntervalMinutes = pollingIntervalMinutes; }

    public Boolean getAutoAnalyzeCommit() { return autoAnalyzeCommit; }
    public void setAutoAnalyzeCommit(Boolean autoAnalyzeCommit) { this.autoAnalyzeCommit = autoAnalyzeCommit; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastCommitSha() { return lastCommitSha; }
    public void setLastCommitSha(String lastCommitSha) { this.lastCommitSha = lastCommitSha; }

    public String getLastCommitMessage() { return lastCommitMessage; }
    public void setLastCommitMessage(String lastCommitMessage) { this.lastCommitMessage = lastCommitMessage; }

    public String getLastCommitAuthor() { return lastCommitAuthor; }
    public void setLastCommitAuthor(String lastCommitAuthor) { this.lastCommitAuthor = lastCommitAuthor; }

    public String getLastCommitTime() { return lastCommitTime; }
    public void setLastCommitTime(String lastCommitTime) { this.lastCommitTime = lastCommitTime; }

    public Integer getFilesCount() { return filesCount; }
    public void setFilesCount(Integer filesCount) { this.filesCount = filesCount; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public String getHealthMessage() { return healthMessage; }
    public void setHealthMessage(String healthMessage) { this.healthMessage = healthMessage; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
