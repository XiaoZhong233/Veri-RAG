package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeComCallbackCryptoTests {

    private static final String TOKEN = "callback-token";
    private static final String CORP_ID = "ww1234567890";
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String AES_KEY = Base64.getEncoder().encodeToString(KEY)
            .substring(0, 43);

    @Test
    void verifiesSignatureAndDecryptsCallback() throws Exception {
        WeComCallbackCrypto crypto = crypto(CORP_ID);
        String timestamp = "1720000000";
        String nonce = "nonce-1";
        String encrypted = encrypt("回调验证成功", CORP_ID);
        String signature = WeComCallbackCrypto.signature(
                TOKEN, timestamp, nonce, encrypted);

        assertEquals("回调验证成功",
                crypto.verifyAndDecrypt(signature, timestamp, nonce, encrypted));
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        WeComCallbackCrypto crypto = crypto(CORP_ID);
        String encrypted = encrypt("hello", CORP_ID);

        assertThrows(IllegalArgumentException.class,
                () -> crypto.verifyAndDecrypt("bad", "1", "2", encrypted));
    }

    @Test
    void rejectsDifferentReceiver() throws Exception {
        WeComCallbackCrypto crypto = crypto(CORP_ID);
        String encrypted = encrypt("hello", "ww-other");
        String signature = WeComCallbackCrypto.signature(TOKEN, "1", "2", encrypted);

        assertThrows(IllegalArgumentException.class,
                () -> crypto.verifyAndDecrypt(signature, "1", "2", encrypted));
    }

    private static WeComCallbackCrypto crypto(String corpId) {
        WeComKfProperties properties = new WeComKfProperties();
        properties.setToken(TOKEN);
        properties.setCorpId(corpId);
        properties.setEncodingAesKey(AES_KEY);
        return new WeComCallbackCrypto(properties);
    }

    private static String encrypt(String message, String receiver) throws Exception {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] receiverBytes = receiver.getBytes(StandardCharsets.UTF_8);
        ByteBuffer plain = ByteBuffer.allocate(16 + 4 + messageBytes.length + receiverBytes.length);
        plain.put(new byte[16]);
        plain.putInt(messageBytes.length);
        plain.put(messageBytes);
        plain.put(receiverBytes);
        byte[] padded = pad(plain.array());

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(KEY, "AES"), new IvParameterSpec(KEY, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static byte[] pad(byte[] value) {
        int padding = 32 - value.length % 32;
        byte[] result = Arrays.copyOf(value, value.length + padding);
        Arrays.fill(result, value.length, result.length, (byte) padding);
        return result;
    }
}
