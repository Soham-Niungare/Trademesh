# TradeMesh

A trading platform: a single-module Spring Boot backend (monolith) and a
Next.js frontend, run as two separate processes — the backend serves no
HTML.

## Repository structure

```
TradeMesh/
├── backend/
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
├── frontend/
│   ├── src/
│   ├── .env.example
│   ├── package.json
│   └── README.md
├── docs/
│   ├── architecture.md
│   ├── market-data.md
│   ├── matching-engine.md
│   ├── websocket.md
│   └── phases/
│       ├── phase-01-backend-setup.md
│       ├── phase-02-matching-engine.md
│       ├── phase-03-trade-execution.md
│       ├── phase-04-authentication.md
│       ├── phase-05-redis-market-data.md
│       ├── phase-06-websocket.md
│       ├── phase-07-frontend.md
│       └── phase-08-testing-hardening.md
├── docker-compose.yml
├── README.md
└── .gitignore
```

## Prerequisites

- Java 21 (JDK)
- Maven
- Docker + Docker Compose (for running Postgres and Redis)
- Node.js 20+ and npm (for the frontend)

## Quick start

Three processes, in this order:

```
docker compose up -d      # 1. Postgres and Redis

cd backend
mvn spring-boot:run       # 2. backend on :8080

cd ../frontend
npm install               # first run only
npm run dev               # 3. frontend on :3000
```

Verify the backend: `curl http://localhost:8080/actuator/health` should
return `200` with `"status":"UP"` and a `db` component also `UP`.

Then open <http://localhost:3000>, which lands on the sign-in page — use
**Create one** to register, which signs you in and redirects to the
dashboard. The frontend talks to the backend across origins, which the
backend allows via `cors.allowed-origins` (see
[`docs/phases/phase-07-frontend.md`](docs/phases/phase-07-frontend.md));
if that is ever misconfigured, every API call fails with a network error
while the page itself still loads.

## Docs

- [`docs/architecture.md`](docs/architecture.md) — high-level system
  overview and known limitations
- [`docs/matching-engine.md`](docs/matching-engine.md) — matching engine
  design (living reference, kept current)
- [`docs/market-data.md`](docs/market-data.md) — Redis market-data
  projection design (living reference, kept current)
- [`docs/websocket.md`](docs/websocket.md) — internal event hook +
  WebSocket real-time design (living reference, kept current)
- [`frontend/README.md`](frontend/README.md) — running and configuring the
  frontend (env vars, project layout)
- [`docs/phases/`](docs/phases/) — a log of what each phase actually
  built, in order

## Phase status

Tracks the project plan's Phase 0–10 checklist. Phase names below are
placeholders for 0 and 8–10 pending the source doc — update this table
once the real names are available. This table is the one part of this
README expected to change every phase; everything else above should stay
put.

| Phase | Status |
|---|---|
| Phase 0 | ✅ Done |
| Phase 1 — Backend Setup ([doc](docs/phases/phase-01-backend-setup.md)) | ✅ Done |
| Phase 2 — Matching Engine Core ([doc](docs/phases/phase-02-matching-engine.md)) | ✅ Done |
| Phase 3 — Trade Execution & Persistence ([doc](docs/phases/phase-03-trade-execution.md)) | ✅ Done |
| Phase 4 — Authentication & Order REST API ([doc](docs/phases/phase-04-authentication.md)) | ✅ Done |
| Phase 5 — Redis Market-Data Projection ([doc](docs/phases/phase-05-redis-market-data.md)) | ✅ Done |
| Phase 6 — WebSocket Real-Time Updates ([doc](docs/phases/phase-06-websocket.md)) | ✅ Done |
| Phase 7 — Frontend ([doc](docs/phases/phase-07-frontend.md)) | ✅ Done |
| Phase 8 — Testing & Hardening ([doc](docs/phases/phase-08-testing-hardening.md)) | ✅ Done |
| Phase 9 | ⬜ Not started |
| Phase 10 | ⬜ Not started |
