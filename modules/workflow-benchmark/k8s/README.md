# Running the benchmark on Kubernetes

Five manifests, one per role, each pinned to a node of its own. What makes the figure worth
anything is the pinning: on a shared cluster the scheduler will happily stack all of it onto one
machine, and you get the laptop measurement back with more ceremony.

## How the isolation works

Two mechanisms, both needed:

**Pod anti-affinity** on `bench-node-exclusive` keeps the benchmark's own roles off each other's
nodes.

**Request sizing** keeps them off everybody else's. Requests of 1000–1500m exceed the spare
requestable CPU of a busy 2-vCPU node, so the scheduler cannot place them next to existing
workloads and Karpenter provisions instead. This is why the requests must not be shrunk to "make
it schedule": doing that puts the database on the same core as the thing measuring it, and the
number then describes the contention.

Check the placement before believing any output:

```bash
kubectl -n ec-bench get pods -o wide
```

Five distinct nodes, none of them shared with another namespace. If two roles share a node, the
run is void.

## Capacity it needs

Six nodes: PostgreSQL, Kafka, two orchestrator pods, a worker and the driver. On 2-vCPU
instances that is **12 vCPU of fresh capacity**, on top of whatever the cluster already runs.

Karpenter's pool limit is the ceiling. Check it first and give it headroom for the run:

```bash
kubectl get nodepool -o jsonpath='{.items[*].spec.limits}'
```

If the limit leaves no room, the last role silently stays `Pending` and the driver waits forever
for processes that never start.

## Running it

```bash
# 1. Publish the image for the commit you are measuring
gh workflow run publish-benchmark-image.yml -f ref=<branch> -f tag=<tag>

# 2. Substitute the tag — never :latest, or the report cannot say what it measured
sed "s|BENCH_IMAGE|<dockerhub-user>/eventconductor-bench:<tag>|g" \
  modules/workflow-benchmark/k8s/*.yaml | kubectl apply -f -

# 3. Wait for the infrastructure and the pods, then check placement
kubectl -n ec-bench get pods -o wide -w

# 4. Read the report
kubectl -n ec-bench logs job/driver -f
```

Tear it down when finished — the nodes are billed while they exist:

```bash
kubectl delete namespace ec-bench
```

## Storage

PostgreSQL runs on an `emptyDir`. A network volume would make this measure Hetzner block storage
latency rather than the engine, and nothing here needs to outlive the run. Record that choice
alongside any figure: it is favourable, and saying so is the difference between a benchmark and a
brochure.

## What this layout can and cannot answer

It answers **engine cost per transition**, which is the metric that survives having the workers
changed underneath it, and it answers it honestly because each role is alone on its node.

It does not answer maximum throughput or a scaling curve. Those need enough spare capacity to
saturate the engine without the load generator and the broker competing for it — on 2-vCPU
instances they cannot. Ask for those separately, on bigger nodes, and say which was which.
