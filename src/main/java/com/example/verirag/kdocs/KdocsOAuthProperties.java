package com.example.verirag.kdocs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 金山文档 OAuth 配置。敏感 App Key 仅从环境变量读取。 */
@Data
@Component
@ConfigurationProperties(prefix = "kdocs.oauth")
public class KdocsOAuthProperties {

    private String appId;

    private String appKey;

    /** 必须与金山开放平台后台登记的回调地址完全一致。 */
    private String redirectUri;

    /** 逗号分隔的 OAuth 权限。 */
    private String scope = "access_personal_files,download_personal_files";
}
