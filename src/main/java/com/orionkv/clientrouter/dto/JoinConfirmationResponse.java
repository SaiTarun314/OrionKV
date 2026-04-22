package com.orionkv.clientrouter.dto;

import com.orionkv.clientrouter.model.RouterRegistrySnapshot;

public record JoinConfirmationResponse(
        boolean confirmed,
        String joiningNodeId,
        RouterRegistrySnapshot registry
) {
}
