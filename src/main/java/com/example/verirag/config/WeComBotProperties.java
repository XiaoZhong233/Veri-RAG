package com.example.verirag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * 企业微信智能机器人长连接配置。
 *
 * <p>刻意不实现 toString，避免 Secret 被配置诊断或日志意外输出。</p>
 */
@Component
@ConfigurationProperties(prefix = "wecom.bot")
public class WeComBotProperties {

    /** 总开关；关闭时不会创建 WeComBotClient，也不会占用机器人的唯一长连接。 */
    private boolean enabled;
    /** 企业微信后台生成的智能机器人唯一标识。 */
    private String botId;
    /** 长连接专用 Secret，不是 Webhook 的 Token 或 EncodingAESKey。 */
    private String secret;
    /** 群聊中展示的机器人名称，用于从“@机器人 问题”中精确移除 mention。 */
    private String displayName = "londonist 助手";
    /** 企业微信智能机器人 WebSocket 服务地址。 */
    private URI websocketUrl = URI.create("wss://openws.work.weixin.qq.com");
    /** 所有企业微信会话在本系统中归属的 t_user.id。 */
    private long userId = 2L;
    /** 应用层 ping 间隔，官方建议约 30 秒。 */
    private Duration heartbeatInterval = Duration.ofSeconds(30);
    /** 将模型增量 token 合并后刷新到企业微信的最小间隔。 */
    private Duration streamUpdateInterval = Duration.ofMillis(2500);
    /** 用户当天首次进入机器人单聊时展示的欢迎语。 */
    private String welcomeMessage = "您好！我是智能房源助手，请问有什么可以帮您？";
    /** 群聊中只 @机器人、没有在同一条消息中携带问题时的引导语。 */
    private String emptyQuestionMessage =
            "请将问题和 @机器人 放在同一条消息中，例如：\n"
                    + "@londonist 助手 UCL附近有什么房源推荐吗？";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public URI getWebsocketUrl() {
        return websocketUrl;
    }

    public void setWebsocketUrl(URI websocketUrl) {
        this.websocketUrl = websocketUrl;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getStreamUpdateInterval() {
        return streamUpdateInterval;
    }

    public void setStreamUpdateInterval(Duration streamUpdateInterval) {
        this.streamUpdateInterval = streamUpdateInterval;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }

    public String getEmptyQuestionMessage() {
        return emptyQuestionMessage;
    }

    public void setEmptyQuestionMessage(String emptyQuestionMessage) {
        this.emptyQuestionMessage = emptyQuestionMessage;
    }
}
