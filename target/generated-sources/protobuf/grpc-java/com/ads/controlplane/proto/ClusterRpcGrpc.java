package com.ads.controlplane.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: controlplane.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ClusterRpcGrpc {

  private ClusterRpcGrpc() {}

  public static final java.lang.String SERVICE_NAME = "ads.controlplane.ClusterRpc";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.ads.controlplane.proto.JoinNodeRequest,
      com.ads.controlplane.proto.MembershipState> getJoinMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Join",
      requestType = com.ads.controlplane.proto.JoinNodeRequest.class,
      responseType = com.ads.controlplane.proto.MembershipState.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.ads.controlplane.proto.JoinNodeRequest,
      com.ads.controlplane.proto.MembershipState> getJoinMethod() {
    io.grpc.MethodDescriptor<com.ads.controlplane.proto.JoinNodeRequest, com.ads.controlplane.proto.MembershipState> getJoinMethod;
    if ((getJoinMethod = ClusterRpcGrpc.getJoinMethod) == null) {
      synchronized (ClusterRpcGrpc.class) {
        if ((getJoinMethod = ClusterRpcGrpc.getJoinMethod) == null) {
          ClusterRpcGrpc.getJoinMethod = getJoinMethod =
              io.grpc.MethodDescriptor.<com.ads.controlplane.proto.JoinNodeRequest, com.ads.controlplane.proto.MembershipState>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Join"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.ads.controlplane.proto.JoinNodeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.ads.controlplane.proto.MembershipState.getDefaultInstance()))
              .setSchemaDescriptor(new ClusterRpcMethodDescriptorSupplier("Join"))
              .build();
        }
      }
    }
    return getJoinMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      com.ads.controlplane.proto.MembershipState> getGetMembershipMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMembership",
      requestType = com.google.protobuf.Empty.class,
      responseType = com.ads.controlplane.proto.MembershipState.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      com.ads.controlplane.proto.MembershipState> getGetMembershipMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, com.ads.controlplane.proto.MembershipState> getGetMembershipMethod;
    if ((getGetMembershipMethod = ClusterRpcGrpc.getGetMembershipMethod) == null) {
      synchronized (ClusterRpcGrpc.class) {
        if ((getGetMembershipMethod = ClusterRpcGrpc.getGetMembershipMethod) == null) {
          ClusterRpcGrpc.getGetMembershipMethod = getGetMembershipMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, com.ads.controlplane.proto.MembershipState>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMembership"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.ads.controlplane.proto.MembershipState.getDefaultInstance()))
              .setSchemaDescriptor(new ClusterRpcMethodDescriptorSupplier("GetMembership"))
              .build();
        }
      }
    }
    return getGetMembershipMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ClusterRpcStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClusterRpcStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClusterRpcStub>() {
        @java.lang.Override
        public ClusterRpcStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClusterRpcStub(channel, callOptions);
        }
      };
    return ClusterRpcStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ClusterRpcBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClusterRpcBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClusterRpcBlockingStub>() {
        @java.lang.Override
        public ClusterRpcBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClusterRpcBlockingStub(channel, callOptions);
        }
      };
    return ClusterRpcBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ClusterRpcFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClusterRpcFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClusterRpcFutureStub>() {
        @java.lang.Override
        public ClusterRpcFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClusterRpcFutureStub(channel, callOptions);
        }
      };
    return ClusterRpcFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void join(com.ads.controlplane.proto.JoinNodeRequest request,
        io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getJoinMethod(), responseObserver);
    }

    /**
     */
    default void getMembership(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMembershipMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ClusterRpc.
   */
  public static abstract class ClusterRpcImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ClusterRpcGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ClusterRpc.
   */
  public static final class ClusterRpcStub
      extends io.grpc.stub.AbstractAsyncStub<ClusterRpcStub> {
    private ClusterRpcStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClusterRpcStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClusterRpcStub(channel, callOptions);
    }

    /**
     */
    public void join(com.ads.controlplane.proto.JoinNodeRequest request,
        io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getJoinMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getMembership(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetMembershipMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ClusterRpc.
   */
  public static final class ClusterRpcBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ClusterRpcBlockingStub> {
    private ClusterRpcBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClusterRpcBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClusterRpcBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.ads.controlplane.proto.MembershipState join(com.ads.controlplane.proto.JoinNodeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getJoinMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.ads.controlplane.proto.MembershipState getMembership(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetMembershipMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ClusterRpc.
   */
  public static final class ClusterRpcFutureStub
      extends io.grpc.stub.AbstractFutureStub<ClusterRpcFutureStub> {
    private ClusterRpcFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClusterRpcFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClusterRpcFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.ads.controlplane.proto.MembershipState> join(
        com.ads.controlplane.proto.JoinNodeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getJoinMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.ads.controlplane.proto.MembershipState> getMembership(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetMembershipMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_JOIN = 0;
  private static final int METHODID_GET_MEMBERSHIP = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_JOIN:
          serviceImpl.join((com.ads.controlplane.proto.JoinNodeRequest) request,
              (io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState>) responseObserver);
          break;
        case METHODID_GET_MEMBERSHIP:
          serviceImpl.getMembership((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getJoinMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.ads.controlplane.proto.JoinNodeRequest,
              com.ads.controlplane.proto.MembershipState>(
                service, METHODID_JOIN)))
        .addMethod(
          getGetMembershipMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.google.protobuf.Empty,
              com.ads.controlplane.proto.MembershipState>(
                service, METHODID_GET_MEMBERSHIP)))
        .build();
  }

  private static abstract class ClusterRpcBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ClusterRpcBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.ads.controlplane.proto.Controlplane.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ClusterRpc");
    }
  }

  private static final class ClusterRpcFileDescriptorSupplier
      extends ClusterRpcBaseDescriptorSupplier {
    ClusterRpcFileDescriptorSupplier() {}
  }

  private static final class ClusterRpcMethodDescriptorSupplier
      extends ClusterRpcBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ClusterRpcMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (ClusterRpcGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ClusterRpcFileDescriptorSupplier())
              .addMethod(getJoinMethod())
              .addMethod(getGetMembershipMethod())
              .build();
        }
      }
    }
    return result;
  }
}
