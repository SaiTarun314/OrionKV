package com.ads.controlplane.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: controlplane.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class GossipRpcGrpc {

  private GossipRpcGrpc() {}

  public static final java.lang.String SERVICE_NAME = "ads.controlplane.GossipRpc";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.ads.controlplane.proto.GossipPayload,
      com.ads.controlplane.proto.MembershipState> getGossipMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Gossip",
      requestType = com.ads.controlplane.proto.GossipPayload.class,
      responseType = com.ads.controlplane.proto.MembershipState.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.ads.controlplane.proto.GossipPayload,
      com.ads.controlplane.proto.MembershipState> getGossipMethod() {
    io.grpc.MethodDescriptor<com.ads.controlplane.proto.GossipPayload, com.ads.controlplane.proto.MembershipState> getGossipMethod;
    if ((getGossipMethod = GossipRpcGrpc.getGossipMethod) == null) {
      synchronized (GossipRpcGrpc.class) {
        if ((getGossipMethod = GossipRpcGrpc.getGossipMethod) == null) {
          GossipRpcGrpc.getGossipMethod = getGossipMethod =
              io.grpc.MethodDescriptor.<com.ads.controlplane.proto.GossipPayload, com.ads.controlplane.proto.MembershipState>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Gossip"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.ads.controlplane.proto.GossipPayload.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.ads.controlplane.proto.MembershipState.getDefaultInstance()))
              .setSchemaDescriptor(new GossipRpcMethodDescriptorSupplier("Gossip"))
              .build();
        }
      }
    }
    return getGossipMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static GossipRpcStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GossipRpcStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GossipRpcStub>() {
        @java.lang.Override
        public GossipRpcStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GossipRpcStub(channel, callOptions);
        }
      };
    return GossipRpcStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static GossipRpcBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GossipRpcBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GossipRpcBlockingStub>() {
        @java.lang.Override
        public GossipRpcBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GossipRpcBlockingStub(channel, callOptions);
        }
      };
    return GossipRpcBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static GossipRpcFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GossipRpcFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GossipRpcFutureStub>() {
        @java.lang.Override
        public GossipRpcFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GossipRpcFutureStub(channel, callOptions);
        }
      };
    return GossipRpcFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void gossip(com.ads.controlplane.proto.GossipPayload request,
        io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGossipMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service GossipRpc.
   */
  public static abstract class GossipRpcImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return GossipRpcGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service GossipRpc.
   */
  public static final class GossipRpcStub
      extends io.grpc.stub.AbstractAsyncStub<GossipRpcStub> {
    private GossipRpcStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GossipRpcStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GossipRpcStub(channel, callOptions);
    }

    /**
     */
    public void gossip(com.ads.controlplane.proto.GossipPayload request,
        io.grpc.stub.StreamObserver<com.ads.controlplane.proto.MembershipState> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGossipMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service GossipRpc.
   */
  public static final class GossipRpcBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<GossipRpcBlockingStub> {
    private GossipRpcBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GossipRpcBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GossipRpcBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.ads.controlplane.proto.MembershipState gossip(com.ads.controlplane.proto.GossipPayload request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGossipMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service GossipRpc.
   */
  public static final class GossipRpcFutureStub
      extends io.grpc.stub.AbstractFutureStub<GossipRpcFutureStub> {
    private GossipRpcFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GossipRpcFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GossipRpcFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.ads.controlplane.proto.MembershipState> gossip(
        com.ads.controlplane.proto.GossipPayload request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGossipMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GOSSIP = 0;

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
        case METHODID_GOSSIP:
          serviceImpl.gossip((com.ads.controlplane.proto.GossipPayload) request,
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
          getGossipMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.ads.controlplane.proto.GossipPayload,
              com.ads.controlplane.proto.MembershipState>(
                service, METHODID_GOSSIP)))
        .build();
  }

  private static abstract class GossipRpcBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    GossipRpcBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.ads.controlplane.proto.Controlplane.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("GossipRpc");
    }
  }

  private static final class GossipRpcFileDescriptorSupplier
      extends GossipRpcBaseDescriptorSupplier {
    GossipRpcFileDescriptorSupplier() {}
  }

  private static final class GossipRpcMethodDescriptorSupplier
      extends GossipRpcBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    GossipRpcMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (GossipRpcGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new GossipRpcFileDescriptorSupplier())
              .addMethod(getGossipMethod())
              .build();
        }
      }
    }
    return result;
  }
}
