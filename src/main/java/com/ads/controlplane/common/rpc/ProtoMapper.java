package com.ads.controlplane.common.rpc;

import com.ads.controlplane.common.dto.GossipRequest;
import com.ads.controlplane.common.dto.GossipResponse;
import com.ads.controlplane.common.dto.JoinRequest;
import com.ads.controlplane.membership.model.MemberRecord;
import com.ads.controlplane.membership.model.MemberStatus;
import com.ads.controlplane.proto.GossipPayload;
import com.ads.controlplane.proto.JoinNodeRequest;
import com.ads.controlplane.proto.MemberRecordProto;
import com.ads.controlplane.proto.MemberStatusProto;
import com.ads.controlplane.proto.MembershipState;

import java.time.Instant;
import java.util.List;

public final class ProtoMapper {

    private ProtoMapper() {
    }

    public static GossipPayload toProto(GossipRequest request) {
        GossipPayload.Builder builder = GossipPayload.newBuilder()
                .setSourceNodeId(request.sourceNodeId() == null ? "" : request.sourceNodeId());
        request.membership().forEach(record -> builder.addMembership(toProto(record)));
        return builder.build();
    }

    public static JoinNodeRequest toProto(JoinRequest request) {
        return JoinNodeRequest.newBuilder()
                .setNodeId(request.nodeId())
                .setAddress(request.address())
                .build();
    }

    public static MembershipState toProto(GossipResponse response) {
        MembershipState.Builder builder = MembershipState.newBuilder()
                .setResponderNodeId(response.responderNodeId() == null ? "" : response.responderNodeId());
        response.membership().forEach(record -> builder.addMembership(toProto(record)));
        return builder.build();
    }

    public static MemberRecordProto toProto(MemberRecord record) {
        return MemberRecordProto.newBuilder()
                .setNodeId(record.nodeId())
                .setAddress(record.address() == null ? "" : record.address())
                .setStatus(toProto(record.status()))
                .setIncarnation(record.incarnation())
                .setLastSeen(record.lastSeen().toString())
                .build();
    }

    public static GossipRequest fromProto(GossipPayload payload) {
        return new GossipRequest(payload.getSourceNodeId(), fromProtoRecords(payload.getMembershipList()));
    }

    public static JoinRequest fromProto(JoinNodeRequest request) {
        return new JoinRequest(request.getNodeId(), request.getAddress());
    }

    public static GossipResponse fromProto(MembershipState state) {
        return new GossipResponse(state.getResponderNodeId(), fromProtoRecords(state.getMembershipList()));
    }

    public static List<MemberRecord> fromProtoRecords(List<MemberRecordProto> records) {
        return records.stream().map(ProtoMapper::fromProto).toList();
    }

    public static MemberRecord fromProto(MemberRecordProto record) {
        return new MemberRecord(
                record.getNodeId(),
                record.getAddress().isBlank() ? null : record.getAddress(),
                fromProto(record.getStatus()),
                record.getIncarnation(),
                Instant.parse(record.getLastSeen())
        );
    }

    private static MemberStatusProto toProto(MemberStatus status) {
        return switch (status) {
            case ALIVE -> MemberStatusProto.ALIVE;
            case SUSPECT -> MemberStatusProto.SUSPECT;
            case DEAD -> MemberStatusProto.DEAD;
        };
    }

    private static MemberStatus fromProto(MemberStatusProto status) {
        return switch (status) {
            case SUSPECT -> MemberStatus.SUSPECT;
            case DEAD -> MemberStatus.DEAD;
            case MEMBER_STATUS_UNSPECIFIED, ALIVE, UNRECOGNIZED -> MemberStatus.ALIVE;
        };
    }
}
