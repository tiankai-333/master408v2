package com.mindskip.xzs.domain.ai;

import java.util.Date;

/**
 * AI Prompt 的稳定业务标识（一个 prompt_key 对应一个 definition）。
 * 内容随版本演进，见 {@link AiPromptVersion}；线上由 {@link AiPromptRelease} 决定服务哪个版本。
 */
public class AiPromptDefinition {
    private Long id;
    private String promptKey;
    private String name;
    private String description;
    /** analysis | system | fragment */
    private String promptKind;
    private Boolean enabled;
    private Integer createUser;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPromptKey() { return promptKey; }
    public void setPromptKey(String promptKey) { this.promptKey = promptKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPromptKind() { return promptKind; }
    public void setPromptKind(String promptKind) { this.promptKind = promptKind; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getCreateUser() { return createUser; }
    public void setCreateUser(Integer createUser) { this.createUser = createUser; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
