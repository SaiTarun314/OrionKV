package com.example.kvstore.dataplane.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TokenUtil {

    private TokenUtil() {
    }

    public static long tokenFor(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            long token = ByteBuffer.wrap(hash, 0, Long.BYTES).getLong();
            return token == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(token);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
