# E-Commerce Microservices — DevOps Practice Project

A deliberately small Spring Boot / MongoDB microservices app whose real purpose is the
delivery pipeline around it: Docker, Jenkins CI/CD, Kubernetes namespaces, Kustomize
overlays, Secrets, ConfigMaps and multi-environment deployment.

---

## 1. Architecture

```
                            ┌──────────────────────────┐
   client ────────────────► │      order-service       │
                            │        :8082             │
                            └───┬──────────────────┬───┘
                                │  HTTP (ClusterIP Service DNS)
                    ┌───────────▼──────┐   ┌───────▼──────────┐
                    │ product-service  │   │   user-service   │
                    │      :8081       │   │      :8083       │
                    └───────────┬──────┘   └───────┬──────────┘
                                │                  │
                                └────────┬─────────┘
                                         ▼
                              ┌────────────────────┐
                              │   MongoDB :27017   │  StatefulSet + PVC
                              │  productdb         │
                              │  orderdb           │
                              │  userdb            │
                              └────────────────────┘
```

| Service | Port | Database | Responsibility |
|---|---|---|---|
| `product-service` | 8081 | `productdb` | Product CRUD |
| `order-service`   | 8082 | `orderdb`   | Create/view orders; validates user + product over HTTP |
| `user-service`    | 8083 | `userdb`    | User CRUD |
| `mongodb`         | 27017 | — | One instance per namespace, three logical databases |

Each environment (`dev`, `staging`, `production`) is a **separate namespace** with its own
MongoDB StatefulSet, PVC, ConfigMap, Secret, Deployments and Services. Nothing is shared
across environments.

| | dev | staging | production |
|---|---|---|---|
| Namespace | `dev` | `staging` | `production` |
| Replicas per service | 1 | 2 | 3 |
| Log level | `DEBUG` | `INFO` | `WARN` |
| CPU request / limit | 100m / 500m | 200m / 750m | 300m / 1 |
| Memory request / limit | 256Mi / 512Mi | 384Mi / 768Mi | 512Mi / 1Gi |
| Mongo storage | 1Gi | 2Gi | 5Gi |
| Extras | — | — | PodDisruptionBudgets, topology spread |
| Manual approval | no | no | **yes** |

### Folder structure

```
ecommerce/
├── product-service/                 # Spring Boot app + Dockerfile
│   ├── src/main/java/com/ecommerce/product/
│   │   ├── controller/  model/  repository/  service/  exception/
│   │   └── ProductServiceApplication.java
│   ├── src/main/resources/application.yml
│   ├── src/test/java/...            # unit tests (Mockito)
│   ├── Dockerfile                   # multi-stage: maven build -> JRE runtime
│   └── pom.xml
├── order-service/                   # same layout + client/ (RestTemplate to the others)
├── user-service/                    # same layout
├── k8s/
│   ├── base/                        # environment-agnostic manifests
│   │   ├── mongodb-statefulset.yaml
│   │   ├── mongodb-service.yaml
│   │   ├── product-service-deployment.yaml
│   │   ├── product-service-service.yaml
│   │   ├── order-service-deployment.yaml
│   │   ├── order-service-service.yaml
│   │   ├── user-service-deployment.yaml
│   │   ├── user-service-service.yaml
│   │   └── kustomization.yaml       # configMapGenerator + secretGenerator + image names
│   └── overlays/
│       ├── dev/         { namespace.yaml, kustomization.yaml }
│       ├── staging/     { namespace.yaml, kustomization.yaml }
│       └── production/  { namespace.yaml, kustomization.yaml, poddisruptionbudget.yaml }
├── docker-compose.yml
├── Jenkinsfile
├── .env.example
└── README.md
```

---

## 2. API

```
# product-service
GET    /api/products           POST   /api/products
GET    /api/products/{id}      PUT    /api/products/{id}      DELETE /api/products/{id}

# user-service
GET    /api/users              POST   /api/users
GET    /api/users/{id}         PUT    /api/users/{id}         DELETE /api/users/{id}

# order-service
GET    /api/orders             GET    /api/orders?userId={id}
GET    /api/orders/{id}        POST   /api/orders

# all services
GET    /actuator/health/readiness
GET    /actuator/health/liveness
```

---

## 3. Run locally with Docker Compose

```bash
cp .env.example .env          # optional: change MONGO_USERNAME / MONGO_PASSWORD
docker compose up --build -d  # first build takes a few minutes (Maven downloads)
docker compose ps
docker compose logs -f order-service
```

Smoke test the whole chain:

```bash
# 1. create a user
USER_ID=$(curl -s -X POST localhost:8083/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Ada Lovelace","email":"ada@example.com","phone":"555-0100"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# 2. create a product
PRODUCT_ID=$(curl -s -X POST localhost:8081/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Mechanical Keyboard","description":"87-key","price":89.99,"quantity":25}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# 3. place an order — order-service calls the other two services to validate
curl -s -X POST localhost:8082/api/orders \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER_ID\",\"productId\":\"$PRODUCT_ID\",\"quantity\":2}" | python3 -m json.tool

curl -s localhost:8082/api/orders | python3 -m json.tool
```

Tear down (`-v` also drops the MongoDB volume):

```bash
docker compose down -v
```

### Run the unit tests

The services target Java 17. If your machine has an older JDK, run Maven in a container:

```bash
for s in product-service order-service user-service; do
  docker run --rm -v "$PWD/$s":/w -v maven-cache:/root/.m2 -w /w \
    maven:3.9-eclipse-temurin-17 mvn -B test
done
```

With a local JDK 17 + Maven, just `cd product-service && mvn test`.

---

## 4. Deploy to Kubernetes

### Prerequisites

```bash
kubectl version --client
kustomize version          # v5+; kubectl's built-in -k also works
minikube start --cpus=4 --memory=6g   # or kind / k3s / any cluster
```

### Point the overlays at your images

Each overlay pins `docker.io/CHANGE_ME/<service>`. Replace it once per environment:

```bash
cd k8s/overlays/dev
kustomize edit set image \
  ecommerce/product-service=docker.io/<your-dockerhub-user>/product-service:dev-latest \
  ecommerce/order-service=docker.io/<your-dockerhub-user>/order-service:dev-latest \
  ecommerce/user-service=docker.io/<your-dockerhub-user>/user-service:dev-latest
```

(Jenkins does exactly this on every deploy — see the `Deploy to Kubernetes` stage.)

### Inspect before applying

`kustomize build` renders the final YAML without touching the cluster — the fastest way to
see what an overlay actually produces:

```bash
kustomize build k8s/overlays/dev        | less
kustomize build k8s/overlays/production | grep -E 'replicas:|namespace:|image:'
```

### Apply

```bash
kubectl apply -k k8s/overlays/dev
kubectl apply -k k8s/overlays/staging
kubectl apply -k k8s/overlays/production
```

### Verify

```bash
kubectl get ns
kubectl -n dev get all
kubectl -n dev get pvc,configmap,secret
kubectl -n dev rollout status deployment/product-service

# replica counts differ per environment
kubectl get deploy -A -l app.kubernetes.io/part-of=ecommerce \
  -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,REPLICAS:.spec.replicas
```

### Reach a service

No Ingress is included — port-forward instead:

```bash
kubectl -n dev port-forward svc/product-service 8081:8081
curl localhost:8081/api/products

# or exec into a throwaway pod and use in-cluster DNS
kubectl -n dev run tmp --rm -it --image=curlimages/curl:8.8.0 --restart=Never -- \
  curl -s http://order-service:8082/actuator/health/readiness
```

### Clean up

```bash
kubectl delete -k k8s/overlays/dev
# PVCs created by a StatefulSet are intentionally not deleted with it:
kubectl -n dev delete pvc --all
```

---

## 5. How the Kustomize layering works

**Base** (`k8s/base/`) holds every manifest with no namespace, generic image names
(`ecommerce/product-service`), and `replicas: 1`. It is never applied directly.

**Overlays** supply the differences:

| Mechanism | What it does |
|---|---|
| `namespace: dev` | Stamps the namespace onto every resource |
| `replicas:` | Sets per-service replica counts (1 / 2 / 3) |
| `configMapGenerator` + `behavior: merge` | Overrides `ENVIRONMENT`, `LOG_LEVEL`; inherits the rest from base |
| `secretGenerator` + `behavior: merge` | Supplies environment-specific MongoDB credentials |
| `images:` | Rewrites image name and tag |
| `patches:` (JSON 6902) | Adjusts CPU/memory and Mongo storage size |
| extra `resources:` | Production adds PodDisruptionBudgets |

Generated ConfigMaps and Secrets get a **content-hash suffix**
(`ecommerce-config-4785g9b49h`). Kustomize rewrites every `configMapKeyRef` /
`secretKeyRef` to match, so changing a config value produces a new name and triggers an
automatic rolling restart. That is why the manifests reference `ecommerce-config` but the
cluster shows a hashed name.

### How the Mongo connection string is assembled

The deployments never hardcode a URI. They compose one from a Secret and a ConfigMap:

```yaml
- name: MONGO_USERNAME     # from Secret mongodb-secret
- name: MONGO_PASSWORD     # from Secret mongodb-secret
- name: MONGO_HOST         # from ConfigMap ecommerce-config
- name: MONGO_PORT         # from ConfigMap ecommerce-config
- name: MONGO_DATABASE
  value: productdb
- name: MONGO_URI
  value: "mongodb://$(MONGO_USERNAME):$(MONGO_PASSWORD)@$(MONGO_HOST):$(MONGO_PORT)/$(MONGO_DATABASE)?authSource=admin"
```

Kubernetes expands `$(VAR)` against env vars declared earlier in the same container.

---

## 6. Network isolation (NetworkPolicies)

A namespace is a **naming** boundary, not a **network** boundary. By default a pod in `dev`
can still reach `product-service.production.svc.cluster.local` with the fully-qualified
name. [k8s/base/networkpolicies.yaml](k8s/base/networkpolicies.yaml) closes that gap, and
it ships in the base so every namespace gets the same four policies.

| Policy | Selects | Effect |
|---|---|---|
| `default-deny-ingress` | all pods | Denies every inbound connection — the baseline |
| `allow-backend-ingress` | the 3 services | Re-opens ports 8081–8083 to pods **in the same namespace only** |
| `allow-mongodb-ingress` | mongodb | Allows 27017 **only** from backend pods |
| `allow-backend-egress` | the 3 services | Backends may talk within the namespace + DNS, nothing else |

Two rules explain the whole file:

- NetworkPolicies are **allow-only and additive**. There is no deny rule — you deny by
  selecting a pod with a policy that lists no matching allow. `default-deny-ingress` has an
  empty `podSelector: {}` (all pods) and no `ingress:` block, so it denies everything; the
  other policies add back exactly what the app needs.
- A `from`/`to` entry with only a `podSelector` (no `namespaceSelector`) means **"same
  namespace as the policy."** That single fact is what produces cross-namespace isolation:
  a dev pod matches none of production's `from` entries, so production denies it.

The egress policy keeps a **mandatory DNS exception** (UDP/TCP 53 to `kube-system`).
Without it every hostname lookup fails and the services can't even resolve `mongodb` — the
most common way a first egress policy bricks a cluster.

> **Enforcement requires a CNI that implements NetworkPolicy** — Calico, Cilium, or Weave.
> minikube's default CNI **silently ignores** these objects (they apply cleanly but do
> nothing). Start with `minikube start --cni=calico` to actually enforce them.

Verify enforcement is real:

```bash
# same namespace: allowed
kubectl -n dev run t --rm -it --image=curlimages/curl:8.8.0 --restart=Never -- \
  curl -sS --max-time 5 http://product-service:8081/api/products        # 200

# cross namespace: should hang then fail once Calico is enforcing
kubectl -n dev run t --rm -it --image=curlimages/curl:8.8.0 --restart=Never -- \
  curl -sS --max-time 5 http://product-service.production:8081/api/products   # timeout
```

---

## 7. Jenkins CI/CD

### Pipeline stages

1. **Checkout** — clone from Git, resolve the image tag (`<env>-<build>-<git-sha>`)
2. **Unit Tests** — all three services in parallel; results published via `junit`
3. **Build (Maven)** — `mvn clean package`, jars archived
4. **Build Docker Images** — one image per service, tagged `<tag>` and `<env>-latest`
5. **Push to Docker Hub** — using the `dockerhub-credentials` credential
6. **Approval** — `input` gate, **production only**
7. **Deploy to Kubernetes** — `kustomize edit set image` → `kustomize build` → `kubectl apply` → `rollout status`
8. **Smoke Test** — curls each service's readiness endpoint from inside the namespace

### Setup

Configure two credentials in Jenkins:

| ID | Kind | Contents |
|---|---|---|
| `dockerhub-credentials` | Username with password | Docker Hub username + access token |
| `kubeconfig` | Secret file | kubeconfig with access to all three namespaces |

Edit one line in the `Jenkinsfile`:

```groovy
DOCKERHUB_USERNAME = 'CHANGE_ME'   // your Docker Hub account
```

Agent tools needed: `git`, `docker`, `kubectl`, `kustomize`. Maven and the JDK are **not**
required — the pipeline runs Maven inside a `maven:3.9-eclipse-temurin-17` container with a
named volume caching `~/.m2`.

Use a **Multibranch Pipeline** job (not a plain Pipeline) so `env.BRANCH_NAME` is set —
the branch→environment mapping depends on it:

```
New Item → Multibranch Pipeline → "ecommerce"
  Branch Sources → your Git repo + credentials
  Behaviours → Discover branches + Discover pull requests from origin
  Build Configuration → by Jenkinsfile → Script Path: Jenkinsfile
```

Add a GitHub webhook → `http://<jenkins>/github-webhook/` (push events).

### Branch → environment → namespace mapping

The pipeline derives the target from the branch when `ENVIRONMENT=auto` (the default):

| Branch | Environment | Namespace | Replicas | Deploys? | Approval |
|---|---|---|---|---|---|
| `main` / `master` | production | `production` | 3 | yes | **yes** |
| `release/*` | staging | `staging` | 2 | yes | no |
| `develop` | dev | `dev` | 1 | yes | no |
| `feature/*`, `bugfix/*`, `PR-*` | none | — | — | **no** (CI only) | — |

Non-mapped branches still run tests + Maven + Docker build (fast feedback) but skip push
and deploy entirely. Override the mapping by setting the `ENVIRONMENT` parameter to a
specific environment instead of `auto`.

Build parameters:

```
ENVIRONMENT = auto | dev | staging | production   (auto = derive from branch)
IMAGE_TAG   = (blank → <env>-<build>-<git-sha>)
SKIP_TESTS  = false                                (rejected for production)
```

Production builds pause at the **Approval** stage until someone clicks *Deploy to
production* (30-minute timeout).

---

## 8. Things worth knowing

- **Secrets in Git.** The overlays contain literal demo passwords so `kustomize build`
  works out of the box. For anything real, delete the `secretGenerator` block and create
  the Secret out-of-band, or use Sealed Secrets / External Secrets Operator:
  ```bash
  kubectl -n production create secret generic mongodb-secret \
    --from-literal=MONGO_USERNAME=prodadmin \
    --from-literal=MONGO_PASSWORD='<strong-password>'
  ```
  If you do this, also set `generatorOptions.disableNameSuffixHash: true` in the base (or
  drop the generator) so the deployments reference the plain name `mongodb-secret`.
- **Password characters.** The MongoDB URI is assembled by string interpolation, so
  passwords must be URL-safe (no `@ : / ?` etc.) or they will break the connection string.
- **Resizing Mongo storage.** `volumeClaimTemplates` are immutable on an existing
  StatefulSet. Changing the overlay's storage size only affects a fresh deployment;
  otherwise delete the StatefulSet (`--cascade=orphan`) and its PVC first.
- **Images must exist.** Deployments will sit in `ImagePullBackOff` until you have pushed
  images to `docker.io/<your-user>/<service>`. For a local cluster you can skip the
  registry entirely: `eval $(minikube docker-env) && docker compose build`, then set the
  overlay images to the local names with `imagePullPolicy: IfNotPresent`.
- **Startup probes.** Each service has a `startupProbe` with `failureThreshold: 30`
  (~150s) so slow JVM boots do not trip the liveness probe and cause a restart loop.
- **One Mongo per namespace.** Simple and isolated, but it is a single replica — fine for
  practice, not for real production. Swapping in a 3-member replica set is a good next
  exercise.
# devops_project
