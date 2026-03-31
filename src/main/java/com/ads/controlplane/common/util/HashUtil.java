package com.ads.controlplane.common.util;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public final class HashUtil {

    private HashUtil() {
    }

    public static long hash(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value.getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }
}
