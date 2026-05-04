package com.orionkv.clientrouter.dto;

public record ClientRestartResponse(
        boolean accepted,
        boolean clearRegistry,
        long delayMs,
        String message
) {
}
