package com.orionkv.clientrouter.service;

import com.orionkv.proto.ClientDeleteResponse;
import com.orionkv.proto.ClientGetResponse;
import com.orionkv.proto.ClientPutResponse;
import com.orionkv.proto.MembershipState;

public interface OrionClusterClient {

    MembershipState getMembership(String grpcAddress);

    ClientPutResponse put(String grpcAddress, String requestId, String key, String value, long timestamp);

    ClientGetResponse get(String grpcAddress, String requestId, String key);

    ClientDeleteResponse delete(String grpcAddress, String requestId, String key, long timestamp);
}
