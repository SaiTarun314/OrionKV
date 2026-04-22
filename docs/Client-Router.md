# Client Router

The client router is a standalone Spring Boot app that keeps a table of OrionKV node gRPC addresses and forwards client `PUT`, `GET`, and `DELETE` requests to any alive node in the cluster.

## Build

```bash
mvn package -DskipTests
```

Artifacts:

- Node service jar: `target/orionkv-0.0.1-SNAPSHOT.jar`
- Client router jar: `target/orionkv-0.0.1-SNAPSHOT-client-router.jar`

## Run

```bash
java -jar target/orionkv-0.0.1-SNAPSHOT-client-router.jar \
  --server.port=8090 \
  --client.router.registry-path=data/client-router-nodes.json \
  --client.router.refresh-interval-ms=5000 \
  --client.router.rpc-timeout-ms=3000
```

## Join Flow

### First node in a brand-new cluster

Ask the router for a seed:

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/seed \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"node-1","grpcAddress":"10.0.0.11:9091"}'
```

If the table is empty, the response will point the node back to itself as seed and store it in the router table.

### Later node joining an existing cluster

Resolve a seed from the current table:

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/seed \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"node-2","grpcAddress":"10.0.0.12:9092"}'
```

The router returns an existing alive node from the table. Start the new OrionKV node with that seed.

After the new node joins and bootstrap finishes, confirm and refresh the router table:

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/confirm \
  -H 'Content-Type: application/json' \
  -d '{"joiningNodeId":"node-2","seedGrpcAddress":"10.0.0.11:9091"}'
```

You can also force a refresh manually:

```bash
curl -X POST http://127.0.0.1:8090/client/nodes/refresh \
  -H 'Content-Type: application/json' \
  -d '{"seedGrpcAddress":"10.0.0.11:9091"}'
```

## Inspect the Table

```bash
curl http://127.0.0.1:8090/client/nodes
```

## Routed Key-Value Operations

Put:

```bash
curl -X PUT http://127.0.0.1:8090/client/kv/user-1 \
  -H 'Content-Type: application/json' \
  -d '{"value":"alice"}'
```

Get:

```bash
curl http://127.0.0.1:8090/client/kv/user-1
```

Delete:

```bash
curl -X DELETE http://127.0.0.1:8090/client/kv/user-1 \
  -H 'Content-Type: application/json' \
  -d '{"timestamp":2000}'
```

The router forwards each operation to any alive OrionKV node in its table using `CoordinationRpc`.
