# Distributed Key-Value Store on Kubernetes

This setup runs a Spring Boot-based distributed key-value store as a 3-node Kubernetes `StatefulSet`.

## What this gives you

- Stable pod identities: `node-0`, `node-1`, `node-2`
- Stable DNS per pod through a headless service
- Gossip bootstrap through `SEED_NODES`
- Local-cluster friendly deployment for Minikube or Docker Desktop Kubernetes
- No external networking or load balancer required

## Directory layout

```text
.
├── Dockerfile
└── k8s
    ├── configmap.yaml
    ├── headless-service.yaml
    └── statefulset.yaml
```

## How pod-to-pod discovery works

The `StatefulSet` is named `node`, so Kubernetes creates pods with ordinal names:

- `node-0`
- `node-1`
- `node-2`

Because the service is headless and named `kv-store-headless`, each pod gets a stable DNS entry:

- `node-0.kv-store-headless`
- `node-1.kv-store-headless`
- `node-2.kv-store-headless`

Inside the cluster, nodes can use either the short form above or the full FQDN:

```text
node-0.kv-store-headless.default.svc.cluster.local
```

## Environment variables exposed to each node

- `NODE_ID`: derived from pod name using the Kubernetes downward API
- `PORT`: loaded from the `ConfigMap`
- `SEED_NODES`: comma-separated bootstrap list from the `ConfigMap`
- `POD_DNS_NAME`: convenience variable with the pod's full DNS name

Example values for `node-1`:

```text
NODE_ID=node-1
PORT=8080
SEED_NODES=node-0.kv-store-headless,node-1.kv-store-headless,node-2.kv-store-headless
POD_DNS_NAME=node-1.kv-store-headless.default.svc.cluster.local
```

## Build the Spring Boot image

From the project root:

```bash
./mvnw clean package
docker build -t kv-store:local .
```

If you use Gradle instead:

```bash
./gradlew bootJar
docker build -t kv-store:local .
```

## Load the image into a local cluster

### Minikube

```bash
minikube image load kv-store:local
```

### Docker Desktop Kubernetes

If Docker Desktop Kubernetes is enabled, the locally built Docker image is usually available directly to the cluster.

## Deploy

```bash
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/headless-service.yaml
kubectl apply -f k8s/statefulset.yaml
```

Check the pods:

```bash
kubectl get pods -o wide
kubectl get pods -l app=kv-store
kubectl get svc kv-store-headless
```

## Verify stable identities

```bash
kubectl get pods -l app=kv-store -o name
```

Expected:

```text
pod/node-0
pod/node-1
pod/node-2
```

Check environment variables inside one pod:

```bash
kubectl exec node-0 -- printenv | grep -E 'NODE_ID|PORT|SEED_NODES|POD_DNS_NAME'
```

## Scale from 3 to 5 nodes

Scale the `StatefulSet`:

```bash
kubectl scale statefulset node --replicas=5
```

Kubernetes will create:

- `node-3`
- `node-4`

They will join the cluster through the original seed list in `SEED_NODES`. You do not need application code changes to scale out, because new nodes only need reachable seed members for initial gossip discovery.

Verify:

```bash
kubectl get pods -l app=kv-store
```

If you want the config to explicitly list more seed nodes later, update the `ConfigMap` and restart the pods:

```bash
kubectl apply -f k8s/configmap.yaml
kubectl rollout restart statefulset/node
```

## Notes for the Spring Boot app

Your application should read:

- `NODE_ID` as the unique node identity
- `PORT` as the server or gossip port
- `SEED_NODES` as the bootstrap peer list

Because identity comes from the pod name, this pattern stays extensible as replica counts grow. No per-node hardcoding is required.
