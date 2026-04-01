package com.orionkv.dataplane.util;

import com.orionkv.common.util.HashUtil;

public final class TokenUtil {

    private TokenUtil() {
    }

    public static long tokenFor(String key) {
        return HashUtil.hash(key);
    }
}
