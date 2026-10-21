package com.dbagent.agent.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * HMAC 서명 계산(설계서 3.3 B안). 서버 검증과 --get 클라이언트가 같은 코드를 써서 서로 어긋나지 않게 한다.
 *
 * 서명 대상 문자열(줄바꿈 \n 으로 연결):
 *   METHOD \n 경로(raw) \n 쿼리(raw, 없으면 빈 문자열) \n 타임스탬프(epoch ms) \n nonce \n SHA-256(본문, 소문자 hex)
 * 서명 = HMAC-SHA256(토큰, 위 문자열) 소문자 hex. javax.crypto 만 써서 Java 8(IBM J9)에서 동작한다.
 */
public final class Signer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Signer() {
    }

    public static String stringToSign(String method, String path, String query, String ts, String nonce, String bodySha256Hex) {
        return method + "\n" + path + "\n" + (query == null ? "" : query) + "\n" + ts + "\n" + nonce + "\n" + bodySha256Hex;
    }

    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    /** 상수 시간 비교 - 서명이 맞는 앞부분의 길이를 응답 시간으로 알아내지 못하게 한다. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }
}
