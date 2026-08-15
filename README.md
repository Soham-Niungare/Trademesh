# Trademesh

A 6-service trading demo application built with Java 21, Spring Boot 3.3, Spring
Cloud Gateway, and gRPC. It simulates registering a user, logging in, placing an
order, having that order executed at the current simulated market price, and
seeing the resulting holding show up in the user's portfolio.

This is a demo / reference architecture, not a production trading system — see
the "known simplification" note in `matching-engine`'s matching logic.

## Services

| Service               | Port(s)          | Protocol         | Stateful   | Responsibility                                                        |
|------------------------|------------------|------------------|------------|-------------------------------------------------------------------------|
| gateway-service        | 8080             | REST (HTTP)      | No         | Single external entry point; routes by path prefix to backend services |
| auth-service            | 8081             | REST             | Yes (Postgres, schema `auth`)      | User registration/login, JWT issuance, BCrypt hashing        |
| order-service           | 8082 (REST), 9090 (gRPC) | REST + gRPC | Yes (Postgres, schema `orders`)   | Accepts orders, dispatches to matching-engine, receives fill callbacks |
| market-data-service     | 8083             | REST             | No (in-memory) | Simulated price feed for 5 symbols, drifts every 3s                |
| matching-engine         | 9091             | gRPC only        | No (stateless) | Executes orders immediately at current market price                |
| portfolio-service       | 8084 (REST), 9092 (gRPC) | REST + gRPC | Yes (Postgres, schema `portfolio`) | Tracks holdings with weighted-average cost basis            |

All REST and gRPC traffic between services stays inside the Docker network;
`docker-compose.yml` also publishes every port to `localhost` for local testing.

## Data flow

```
                                   ┌─────────────────────┐
                        HTTP       │   gateway-service    │
        client ───────────────────▶      (:8080)          │
                                   └─────────┬────────────┘
                     ┌───────────┬───────────┼───────────────┬───────────────┐
                     │           │           │               │               │
               /auth/**    /orders/**   /market/**      /portfolio/**        │
                     │           │           │               │               │
                     ▼           ▼           ▼               ▼               │
           ┌────────────┐ ┌────────────┐ ┌───────────────┐ ┌────────────────┐│
           │auth-service│ │order-service│ │market-data-svc│ │portfolio-service││
           │  (:8081)   │ │  (:8082)   │ │    (:8083)    │ │    (:8084)      ││
           └─────┬──────┘ └─────┬──────┘ └───────┬───────┘ └────────┬────────┘│
                 │              │ gRPC           │ HTTP (JDK          │        │
                 │              │ SubmitOrder    │  HttpClient)       │        │
              Postgres          ▼                │                   │        │
             schema: auth  ┌─────────────────────▼───────────────┐   │        │
                            │         matching-engine (:9091)      │   │        │
                            │   (stateless, gRPC only, no HTTP)    │   │        │
                            └───────┬───────────────────┬─────────┘   │        │
                                    │ gRPC                │ gRPC        │        │
                                    │ UpdateOrderStatus    │ UpdateHolding        │
                                    ▼                     ▼                     │
                            order-service (:9090)   portfolio-service (:9092) ◀──┘
                            marks order FILLED       updates weighted-avg
                            (Postgres schema:         holding
                             orders)                  (Postgres schema:
                                                        portfolio)
```

End-to-end flow for a single order:

1. Client hits `gateway-service` on `:8080`, which proxies by path prefix.
2. `POST /orders` is handled by `order-service`: the order is saved as
   `PENDING` in Postgres and `201 Created` is returned to the client
   immediately.
3. Asynchronously, `order-service` calls `matching-engine` over gRPC
   (`SubmitOrder`).
4. `matching-engine` fetches the current price for the order's symbol from
   `market-data-service` via a plain JDK `HttpClient` GET call.
5. `matching-engine` calls back over gRPC to `order-service`
   (`UpdateOrderStatus` → `FILLED`) and to `portfolio-service`
   (`UpdateHolding`).
6. `portfolio-service` updates the user's holding, recomputing the
   weighted-average cost basis on buys.
7. The client polls `GET /orders/{orderId}` to see the status flip to
   `FILLED`, and `GET /portfolio/{userId}` to see the resulting holding.

## Running locally

Requires Docker and Docker Compose.

```bash
docker compose up --build
```

This builds all 6 service images (each via its own multi-stage Dockerfile,
built from the repo root so the Maven reactor can resolve the shared
`common` module) and starts Postgres plus all services. Postgres is
initialized with three schemas — `auth`, `orders`, `portfolio` — via
`db/init-schemas.sql`.

Once everything is healthy, the gateway is reachable at `http://localhost:8080`.

## End-to-end curl walkthrough

```bash
# 1. Register a user
curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"hunter22222"}'
# -> 201 {"userId":"..."}

# 2. Log in to get a JWT
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"hunter22222"}'
# -> 200 {"token":"eyJ...","expiresIn":3600}

TOKEN="<paste token here>"
USER_ID="<paste userId here>"

# (optional) validate the token
curl -s http://localhost:8080/auth/validate -H "Authorization: Bearer $TOKEN"

# 3. Place an order
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$USER_ID\",\"symbol\":\"TMSH\",\"side\":\"BUY\",\"quantity\":10,\"price\":142.50,\"type\":\"MARKET\"}"
# -> 201 { "orderId": "...", "status": "PENDING", ... }

ORDER_ID="<paste orderId here>"

# 4. Check the order — poll until status flips to FILLED
curl -s http://localhost:8080/orders/$ORDER_ID

# 5. Check the resulting portfolio
curl -s http://localhost:8080/portfolio/$USER_ID
# -> 200 [{"symbol":"TMSH","quantity":10,"avgPrice":...}]
```

You can also check live simulated prices at any time:

```bash
curl -s http://localhost:8080/market/prices
curl -s http://localhost:8080/market/prices/TMSH
```

## Repository structure

This repo contains the application code only. Kubernetes manifests and Argo
CD configuration live in a separate `trademesh-gitops` repository — no
deployment/cluster configuration is kept here.

```
trademesh/
├── pom.xml                    # parent POM
├── common/                    # shared gRPC/protobuf contracts
├── auth-service/
├── order-service/
├── market-data-service/
├── matching-engine/
├── portfolio-service/
├── gateway-service/
├── db/init-schemas.sql
└── docker-compose.yml
```
