package com.qingfeng.music.server.web.publicapi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** WeChat official-account push in JSON safe mode. */
final class WechatCardPush {
    private final ObjectMapper json = new ObjectMapper();
    private final String token;
    private final byte[] aesKey;
    private final byte[] appId;

    record Text(String openId, String text, long createdAt) {}

    WechatCardPush(String token, String encodingAesKey, String appId) {
        if (token.isBlank() || !encodingAesKey.matches("[A-Za-z0-9+/]{43}") || appId.isBlank())
            throw new IllegalArgumentException("WeChat card push requires the callback token, encoding AES key and app ID");
        this.token = token;
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        this.appId = appId.getBytes(StandardCharsets.UTF_8);
    }

    String challenge(String signature, String timestamp, String nonce, String echo) {
        requireSignature(signature, timestamp, nonce);
        if (echo.length() > 256) throw invalid();
        return echo;
    }

    Text accept(String signature, String timestamp, String nonce, byte[] body) {
        try {
            String encrypted = json.readTree(body).path("Encrypt").asString("");
            if (encrypted.isEmpty()) throw invalid();
            requireSignature(signature, timestamp, nonce, encrypted);
            JsonNode message = json.readTree(decrypt(encrypted));
            if (!"text".equals(message.path("MsgType").asString(""))) return null;
            String openId = message.path("FromUserName").asString("");
            long createdAt = message.path("CreateTime").asLong(0);
            if (!openId.matches("[A-Za-z0-9_-]{1,128}") || createdAt <= 0) throw invalid();
            return new Text(openId, message.path("Content").asString("").strip(), createdAt);
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw invalid();
        }
    }

    private byte[] decrypt(String encrypted) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesKey, 0, 16));
        byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(encrypted));
        if (plaintext.length < 21 || plaintext.length % 32 != 0) throw invalid();
        int padding = Byte.toUnsignedInt(plaintext[plaintext.length - 1]);
        if (padding < 1 || padding > 32 || padding > plaintext.length - 20) throw invalid();
        int end = plaintext.length - padding;
        for (int i = end; i < plaintext.length; i++) if (plaintext[i] != padding) throw invalid();
        int length = ByteBuffer.wrap(plaintext, 16, 4).getInt();
        if (length <= 0 || length != end - 20 - appId.length) throw invalid();
        if (!MessageDigest.isEqual(appId, Arrays.copyOfRange(plaintext, 20 + length, end))) throw invalid();
        return Arrays.copyOfRange(plaintext, 20, 20 + length);
    }

    private void requireSignature(String signature, String timestamp, String nonce, String... content) {
        if (signature == null || !signature.matches("[0-9a-f]{40}") || !timestamp.matches("[0-9]{1,20}")
                || !nonce.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        String[] fields = new String[3 + content.length];
        fields[0] = token;
        fields[1] = timestamp;
        fields[2] = nonce;
        System.arraycopy(content, 0, fields, 3, content.length);
        Arrays.sort(fields);
        try {
            byte[] actual = MessageDigest.getInstance("SHA-1").digest(String.join("", fields).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(signature))) throw invalid();
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.FORBIDDEN); }
}
