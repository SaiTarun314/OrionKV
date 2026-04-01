package com.orionkv.common.rpc;

import com.orionkv.common.dto.GossipRequest;
import com.orionkv.common.dto.GossipResponse;
import com.orionkv.common.dto.JoinRequest;

public interface ControlPlaneClient {

    GossipResponse gossip(String peerAddress, GossipRequest request);

    GossipResponse join(String seedAddress, JoinRequest request);

    GossipResponse getMembership(String peerAddress);
}
