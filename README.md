# TradeMesh

A single-module Spring Boot backend (monolith) for a trading platform.

## Repository structure

```
TradeMesh/
├── backend/
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
├── docs/
│   ├── architecture.md
│   ├── matching-engine.md
│   └── phases/
│       ├── phase-01-backend-setup.md
│       ├── phase-02-matching-engine.md
│       ├── phase-03-trade-execution.md
│       └── phase-04-authentication.md
├── docker-compose.yml
├── README.md
└── .gitignore
```

## Prerequisites

- Java 21 (JDK)
- Maven
- Docker + Docker Compose (for running Postgres)

## Quick start

```
docker compose up -d      # starts Postgres
cd backend
mvn spring-boot:run       # starts the app against it
```

Verify it's up: `curl http://localhost:8080/actuator/health` should
return `200` with `"status":"UP"` and a `db` component also `UP`.

## Docs

- [`docs/architecture.md`](docs/architecture.md) — high-level system
  overview and known limitations
- [`docs/matching-engine.md`](docs/matching-engine.md) — matching engine
  design (living reference, kept current)
- [`docs/phases/`](docs/phases/) — a log of what each phase actually
  built, in order

## Phase status

Tracks the project plan's Phase 0–10 checklist. Phase names below are
placeholders for 0 and 6–10 pending the source doc — update this table
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
| Phase 5 — Redis | 🔜 Next |
| Phase 6 | ⬜ Not started |
| Phase 7 | ⬜ Not started |
| Phase 8 | ⬜ Not started |
| Phase 9 | ⬜ Not started |
| Phase 10 | ⬜ Not started |
