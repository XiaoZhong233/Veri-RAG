package com.example.verirag.controller;

import com.example.verirag.integration.wecom.WeComCallbackCrypto;
import com.example.verirag.integration.wecom.WeComKfMessageService;
import com.example.verirag.integration.wecom.WeComXml;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 企业微信“微信客服”URL 校验与消息事件回调。 */
@RestController
@RequestMapping("/api/wecom/kf/callback")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComKfCallbackController {

    private final WeComCallbackCrypto crypto;
    private final WeComKfMessageService messageService;

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam("msg_signature") String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(crypto.verifyAndDecrypt(signature, timestamp, nonce, echostr));
        } catch (IllegalArgumentException ex) {
            log.warn("event=wecom.kf.callback_verify_rejected reason={}", ex.getMessage());
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("invalid");
        }
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
            MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(
            @RequestParam("msg_signature") String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestBody String body) {
        try {
            String encrypted = WeComXml.value(body, "Encrypt");
            String eventXml = crypto.verifyAndDecrypt(
                    signature, timestamp, nonce, encrypted);
            String event = WeComXml.value(eventXml, "Event");
            if ("kf_msg_or_event".equals(event)) {
                String callbackToken = WeComXml.value(eventXml, "Token");
                String openKfId = WeComXml.value(eventXml, "OpenKfId");
                if (!StringUtils.hasText(callbackToken) || !StringUtils.hasText(openKfId)) {
                    throw new IllegalArgumentException("Incomplete kf_msg_or_event callback");
                }
                messageService.handleNotification(callbackToken, openKfId);
            } else {
                log.info("event=wecom.kf.callback_ignored type={}", event);
            }
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("success");
        } catch (IllegalArgumentException ex) {
            log.warn("event=wecom.kf.callback_rejected reason={}", ex.getMessage());
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("invalid");
        }
    }

}
