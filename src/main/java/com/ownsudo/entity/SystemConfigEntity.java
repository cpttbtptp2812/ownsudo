package com.ownsudo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "system_config")
public class SystemConfigEntity {

    @Id
    @Column(length = 64)
    private String configKey;

    @Column(columnDefinition = "TEXT")
    private String configValue;

    public SystemConfigEntity() {}

    public SystemConfigEntity(String configKey, String configValue) {
        this.configKey = configKey;
        this.configValue = configValue;
    }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }

    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
}