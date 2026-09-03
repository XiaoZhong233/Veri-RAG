package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** 企业微信回调签名验证与 AES-CBC 消息解密。 */
@Component
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComCallbackCrypto {

    private static final int RANDOM_PREFIX_LENGTH = 16;
    private static final int PKCS7_BLOCK_SIZE = 32;
    private final String token;
    private final String receiverId;
    private final byte[] aesKey;

    public WeComCallbackCrypto(WeComKfProperties properties) {
        this.token = requireText(properties.getToken(), "wecom.kf.token");
        this.receiverId = requireText(properties.getCorpId(), "wecom.kf.corp-id");
        String encodingAesKey = requireText(
                properties.getEncodingAesKey(), "wecom.kf.encoding-aes-key");
        if (encodingAesKey.length() != 43) {
            throw new IllegalStateException("wecom.kf.encoding-aes-key must contain 43 characters");
        }
        try {
            this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("wecom.kf.encoding-aes-key is not valid Base64", ex);
        }
        if (aesKey.length != 32) {
            throw new IllegalStateException("wecom.kf.encoding-aes-key must decode to 32 bytes");
        }
    }

    /** 校验 msg_signature 后解密回调密文，并校验末尾 CorpID。 */
    public String verifyAndDecrypt(
            String signature, String timestamp, String nonce, String encrypted) {
        if (!StringUtils.hasText(signature)
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(encrypted)) {
            throw new IllegalArgumentException("Missing WeCom callback signature parameters");
        }
        // 部分代理/表单解码器会把查询串中未转义的 Base64 '+' 变为空格。
        encrypted = encrypted.replace(' ', '+');
        String expected = signature(token, timestamp, nonce, encrypted);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Invalid WeCom callback signature");
        }
        return decrypt(encrypted);
    }

    static String signature(String token, String timestamp, String nonce, String encrypted) {
        String[] values = {token, timestamp, nonce, encrypted};
        Arrays.sort(values);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(
                    String.join("", values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-1 is unavailable", ex);
        }
    }

    private String decrypt(String encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            byte[] padded = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            byte[] plain = unpad(padded);
            if (plain.length < RANDOM_PREFIX_LENGTH + Integer.BYTES) {
                throw new IllegalArgumentException("Invalid WeCom encrypted payload");
            }
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            buffer.position(RANDOM_PREFIX_LENGTH);
            int messageLength = buffer.getInt();
            int messageStart = RANDOM_PREFIX_LENGTH + Integer.BYTES;
            int messageEnd = messageStart + messageLength;
            if (messageLength < 0 || messageEnd > plain.length) {
                throw new IllegalArgumentException("Invalid WeCom message length");
            }
            String message = new String(
                    plain, messageStart, messageLength, StandardCharsets.UTF_8);
            String actualReceiver = new String(
                    plain, messageEnd, plain.length - messageEnd, StandardCharsets.UTF_8);
            if (!receiverId.equals(actualReceiver)) {
                throw new IllegalArgumentException("WeCom callback receiver does not match CorpID");
            }
            return message;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to decrypt WeCom callback", ex);
        }
    }

    private static byte[] unpad(byte[] padded) {
        if (padded.length == 0) {
            throw new IllegalArgumentException("Empty WeCom encrypted payload");
        }
        int padding = Byte.toUnsignedInt(padded[padded.length - 1]);
        if (padding < 1 || padding > PKCS7_BLOCK_SIZE || padding > padded.length) {
            throw new IllegalArgumentException("Invalid WeCom PKCS#7 padding");
        }
        for (int i = padded.length - padding; i < padded.length; i++) {
            if (Byte.toUnsignedInt(padded[i]) != padding) {
                throw new IllegalArgumentException("Invalid WeCom PKCS#7 padding");
            }
        }
        return Arrays.copyOf(padded, padded.length - padding);
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + " must not be blank when WeCom KF is enabled");
        }
        return value.trim();
    }
}
