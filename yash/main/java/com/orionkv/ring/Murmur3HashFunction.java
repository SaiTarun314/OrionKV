package com.orionkv.ring;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;

public final class Murmur3HashFunction implements HashFunction {
    @Override
    public long hashToLong(String input) {
        return Hashing.murmur3_128().hashString(input, StandardCharsets.UTF_8).asLong();
    }
}
