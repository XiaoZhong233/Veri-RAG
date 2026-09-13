package com.example.verirag.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WeComCustomerMessage {
    private Long id;
    private String messageId;
    private String speaker;
    private String messageType;
    private String content;
    private LocalDateTime sentAt;
}
