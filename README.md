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
