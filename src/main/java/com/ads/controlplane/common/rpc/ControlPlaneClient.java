package com.ads.controlplane.common.rpc;

import com.ads.controlplane.common.dto.GossipRequest;
import com.ads.controlplane.common.dto.GossipResponse;
import com.ads.controlplane.common.dto.JoinRequest;

public interface ControlPlaneClient {

    GossipResponse gossip(String peerAddress, GossipRequest request);

    GossipResponse join(String seedAddress, JoinRequest request);

    GossipResponse getMembership(String peerAddress);
}
