# demo/.devops — self-contained deployment of the demo

Everything needed to deploy the full demo to a Kubernetes cluster, self-contained in this repo
(no dependency on any external deployment project). Reference deployment: CloudFleet/Hetzner with
Karpenter autoscaling, a CloudFleet LoadBalancer, and block (RWO) `hcloud-volumes` storage.

## Architecture
```
Internet → nginx-certbot (TLS, reverse proxy)
   ├─ app.mateu.io   → api-gw (Spring Cloud Gateway) ──┬─ /**  shell (@KeycloakSecured, ONLY auth)
   │                                                    ├─ /_users /_content-service /_control-plane /_booking
   │                                                    ├─ /_workflow → orchestrator (auth OFF)
   │                                                    ├─ /_forms → forms (auth OFF)
   │                                                    └─ /ai /static
   ├─ auth.mateu.io  → Keycloak (realm mateu, client demo)
   └─ grafana.mateu.io → Grafana (Prometheus + Loki + Tempo)
```
Only the shell authenticates (Keycloak); backends sit behind the gateway with no auth. All demo
services run Mateu 3.0-alpha.273. EventConductor engine chart lives at `charts/eventconductor`.

## Layout
- `eventconductor/values.yaml` — Helm values for `charts/eventconductor` (orchestrator/forms/rules
  + Postgres + Redpanda), security OFF (fronted by the shell), git-import of `.ec` demo workflows,
  OTLP tracing → Tempo. **Sanitized** (`CHANGE_ME`).
- `keycloak/` — Keycloak Deployment + `mateu-realm.json` (realm `mateu`, client `demo`, user demo/demo).
- `postgres/postgres-demo.yaml` — shared Postgres for the business services. **Sanitized**.
- `services/` — the 8 demo services (`demo-services.yaml`), the task `worker.yaml`, and the common
  `Dockerfile` used to build every service image from its pre-built jar.
- `observability/` — Grafana LGTM Helm values (`kps-`/`loki-`/`tempo-`/`alloy-values.yaml`),
  `servicemonitors.yaml`, and `dashboards/` (import into Grafana).
- `nginx/` — the `nginx-certbot` reverse-proxy build context (Dockerfile, entrypoint, vhosts).

## Prerequisites
- A cluster with a default StorageClass (`hcloud-volumes` here) and a LoadBalancer/Ingress path.
- `kubectl`, `helm`, `docker` (buildx). Images build for `linux/amd64` (pin `nodeSelector` if the
  nodepool can also provision arm64).
- DNS `app` / `auth` / `grafana`.mateu.io → the LB IP.

## Build the service images
Don't, normally: run the **Publish demo images** workflow, which builds them from a commit and
labels every image with the revision it came from.

```sh
gh workflow run publish-demo-images.yml -f tag=demo-0.1.0
```

Then point `charts/eventconductor-demo/Chart.yaml`'s `appVersion` at that tag. The images it
pushes are `miguelperezcolom/<service>` — the names `charts/eventconductor-demo` deploys.

By hand, if you must (Mateu apps; **Lombok services need JDK 21**):
```sh
mvn -DskipTests install -pl modules/shared -am    # five services compile against it, and
                                                  # 1.0-SNAPSHOT is never published
mvn -f demo/pom.xml -DskipTests package           # one build, all seven jars
docker buildx build --platform linux/amd64 -f demo/<svc>/Dockerfile.runtime \
  -t miguelperezcolom/<svc>:<tag> --push demo/<svc>
```
`demo/.devops/services/Dockerfile` is the older common build, taking the jar as a `JAR` build-arg;
the per-service `Dockerfile.runtime` files replaced it and are what the chart's images are built
from. The worker (`apps/worker-standalone-app`) → `miguelperezcolom/mateu-demo-worker`.

## Secrets (create out-of-band, not committed — see `.gitignore`)
```sh
kubectl create namespace demo
kubectl create namespace eventconductor
# Docker Hub pull secret (private mateu-demo-* images) in both namespaces + attach to the SAs
for ns in demo eventconductor; do
  kubectl create secret docker-registry dockerhub -n $ns \
    --docker-server=https://index.docker.io/v1/ \
    --docker-username=miguelperezcolom --docker-password=<DOCKERHUB_TOKEN>
  kubectl patch serviceaccount default -n $ns -p '{"imagePullSecrets":[{"name":"dockerhub"}]}'
done
# Keycloak admin + realm
kubectl create secret generic keycloak-admin -n demo --from-literal=password=<KC_ADMIN_PW>
kubectl create configmap keycloak-realm -n demo --from-file=mateu-realm.json=keycloak/mateu-realm.json
# EventConductor admin secret (referenced by the chart even with security off)
kubectl create secret generic ec-admin -n eventconductor --from-literal=SECURITY_PASSWORD=<PW>
```
Fill the `CHANGE_ME` values in `eventconductor/values.yaml` and `postgres/postgres-demo.yaml`
(and `observability/kps-values.yaml` Grafana password) before applying.

## Deploy
```sh
# 1. EventConductor engine (Helm)
helm upgrade --install ec charts/eventconductor -n eventconductor -f demo/.devops/eventconductor/values.yaml
# 2. Demo data + Keycloak + services
kubectl apply -f demo/.devops/postgres/postgres-demo.yaml
kubectl apply -f demo/.devops/keycloak/keycloak.yaml
kubectl apply -f demo/.devops/services/demo-services.yaml
kubectl apply -f demo/.devops/services/worker.yaml
# business services need the shared DB (Spring reads these over their application.yaml):
for s in users-service content-service control-plane-service booking-service; do
  kubectl set env deployment/$s -n demo \
    SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-demo:5432/demo \
    SPRING_DATASOURCE_USERNAME=demo SPRING_DATASOURCE_PASSWORD=<DEMO_PG_PW>
done
kubectl set env deployment/ia-agent-service -n demo ANTHROPIC_API_KEY=<key>   # optional (AI)
# 3. Observability (Grafana LGTM)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts && helm repo update
kubectl create namespace observability
helm upgrade --install kps prometheus-community/kube-prometheus-stack -n observability -f demo/.devops/observability/kps-values.yaml
helm upgrade --install loki  grafana/loki  -n observability -f demo/.devops/observability/loki-values.yaml
helm upgrade --install alloy grafana/alloy -n observability -f demo/.devops/observability/alloy-values.yaml
helm upgrade --install tempo grafana/tempo -n observability -f demo/.devops/observability/tempo-values.yaml
kubectl apply -f demo/.devops/observability/servicemonitors.yaml
# import observability/dashboards/*.json into Grafana
# 4. Reverse proxy (build + deploy the nginx-certbot image, then a LoadBalancer Service for it)
docker buildx build --platform linux/amd64,linux/arm64 -t miguelperezcolom/nginx-certbot:<ver> --push demo/.devops/nginx
```

## Gotchas learned (all reflected in these files)
- **Prometheus uses `emptyDir`** — the Hetzner CSI rejects the operator's PVC ("invalid input in
  field 'labels'"). Loki/Tempo PVCs are fine.
- **Loki accepts old/out-of-order samples** — Alloy replays each pod's log history on start.
- **postgres/redpanda**: `PGDATA` subdir + `fsGroup` + `strategy: Recreate` on RWO volumes
  (baked into `charts/eventconductor`).
- **`nodeSelector: kubernetes.io/arch: amd64`** on every pod — images are amd64-only.
- **New Docker Hub repos are private** → the `dockerhub` pull secret is required.
- **Worker**: the test worker — it plays back the scenario a process states in its `TEST_CONFIG`
  variable and records what it was given (`worker.persistence=jpa`, or
  `SPRING_PROFILES_ACTIVE=memory` for no database). It runs no engine, so there is no
  `workflow.mode` on it. Inject events with `rpk topic produce upstream --compression none`
  (Alpine consumers can't decompress snappy).
