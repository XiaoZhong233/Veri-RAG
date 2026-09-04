package com.example.verirag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * 企业微信“微信客服”API 配置。
 *
 * <p>不实现 toString，避免 Secret、Token 和 EncodingAESKey 出现在日志或诊断信息中。</p>
 */
@Component
@ConfigurationProperties(prefix = "wecom.kf")
public class WeComKfProperties {

    private boolean enabled;
    private String corpId;
    private String secret;
    private String token;
    private String encodingAesKey;
    private long userId = 2L;
    private URI apiBaseUrl = URI.create("https://qyapi.weixin.qq.com");
    private int syncLimit = 1000;
    private Duration progressDelay = Duration.ofMillis(1500);
    private Duration messageMergeWindow = Duration.ofMillis(900);
    private String progressMessage = "正在检索资料，请稍候…";
    private String unsupportedMessage = "您好，目前智能客服仅支持文字消息，请用文字描述您的问题。";
    private String errorMessage = "抱歉，智能客服暂时无法处理您的问题，请稍后再试或联系人工客服。";
    private String handoffMessage = "已为您转接人工顾问，请稍候。";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEncodingAesKey() {
        return encodingAesKey;
    }

    public void setEncodingAesKey(String encodingAesKey) {
        this.encodingAesKey = encodingAesKey;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public URI getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(URI apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public int getSyncLimit() {
        return syncLimit;
    }

    public void setSyncLimit(int syncLimit) {
        this.syncLimit = syncLimit;
    }

    public Duration getProgressDelay() {
        return progressDelay;
    }

    public void setProgressDelay(Duration progressDelay) {
        this.progressDelay = progressDelay;
    }

    public Duration getMessageMergeWindow() {
        return messageMergeWindow;
    }

    public void setMessageMergeWindow(Duration messageMergeWindow) {
        this.messageMergeWindow = messageMergeWindow;
    }

    public String getProgressMessage() {
        return progressMessage;
    }

    public void setProgressMessage(String progressMessage) {
        this.progressMessage = progressMessage;
    }

    public String getUnsupportedMessage() {
        return unsupportedMessage;
    }

    public void setUnsupportedMessage(String unsupportedMessage) {
        this.unsupportedMessage = unsupportedMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getHandoffMessage() {
        return handoffMessage;
    }

    public void setHandoffMessage(String handoffMessage) {
        this.handoffMessage = handoffMessage;
    }
}
