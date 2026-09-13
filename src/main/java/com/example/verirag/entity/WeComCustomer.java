package com.example.verirag.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WeComCustomer {
    private Long id;
    private String openKfId;
    private String externalUserId;
    private String nickname;
    private String avatar;
    private LocalDateTime profileUpdatedAt;
    private LocalDateTime lastSeenAt;
    private String serviceSummary;
    private LocalDateTime summaryUpdatedAt;
}
