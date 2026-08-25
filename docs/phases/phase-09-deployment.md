# Phase 9: Deployment

Phase 9 is split into three stages. **This document covers Stages 1 and 2**, and
is expected to grow as Stage 3 lands:

| Stage | Scope | Status |
|---|---|---|
| 1 | Container images for both apps, verified locally | ✅ Done |
| 2 | Kubernetes manifests | ✅ Done (written, not yet applied) |
| 3 | CI/CD (Jenkins + Kaniko + Argo CD) | ⬜ Not started |

Neither stage touches Jenkins, a registry, or a live cluster. Stage 1 produced
two production-shaped images that build and run correctly on their own, plus the
configuration cleanup that makes them deployable without baked-in values. Stage 2
produced the manifests that will run them, written to be reviewed and applied by
hand before any GitOps automation exists.

---

# Stage 1 — Containerization

## What was built

- **`backend/Dockerfile`** — the Phase 1 skeleton (already multi-stage, already
  a JRE runtime) extended with a non-root user and a `HEALTHCHECK`.
- **`frontend/Dockerfile`** — new. Three stages: dependency install, build,
  then a runtime stage carrying only Next.js's standalone output.
- **`frontend/next.config.ts`** — `output: "standalone"` enabled. The only
  application-code change in the stage, and only because the runtime image
  depends on it.
- **`backend/.dockerignore` / `frontend/.dockerignore`** — new.
- **`docker-compose.apps.yml`** — a local verification harness, not a
  deployment artifact.

### Why the compose harness is a separate file

`docker-compose.yml` is the documented developer workflow: `docker compose up -d`
starts Postgres and Redis, and the apps run from source (see `README.md` and
`frontend/README.md`). Folding the app containers into it would silently change
what that documented command does for everyone. Two files merge into one
Compose project, so the app services still resolve `postgres` and `redis` by
name:

```
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
```

## The frontend's build-time/runtime split

`NEXT_PUBLIC_*` values are **not runtime configuration**. Next.js substitutes
every `process.env.NEXT_PUBLIC_X` reference with a string literal while
compiling the client bundle, so by the time a container starts they are already
inside the JavaScript served to the browser. Setting a different value on a
running container does nothing; changing one requires rebuilding the image.

They are therefore wired as build `ARG`s, defaulted to the values in
`frontend/.env.example` so a bare `docker build` still produces a
locally-working image.

Two consequences worth stating plainly, because both are easy to get wrong
later:

- **These must be addresses the *browser* can reach, not Docker-internal
  hostnames.** The app has no server-side proxy — the browser calls the backend
  directly — so `http://backend:8080` would resolve only inside Compose and
  break every request from a real user.
- **`.env*` is in `frontend/.dockerignore` deliberately, not for tidiness.** A
  `.env.local` present in the build context could supply these values instead of
  the build ARGs, silently producing an image pinned to `localhost` regardless
  of what was passed to `docker build`. Keeping them out of the context removes
  the ambiguity.

The ARG wiring was verified by building a throwaway image with a different API
base URL and grepping the emitted bundle: it contained only the new URL, with
no trace of the default.

## A bug found during verification: healthcheck vs. IPv6

The frontend container came up **`unhealthy` while serving traffic perfectly**.

The standalone server binds the IPv4 wildcard (`0.0.0.0:3000`), but inside the
image `localhost` resolves to IPv6 `::1` first, where nothing is listening — so
the probe got connection-refused. `127.0.0.1` connected; `localhost` did not.

Both healthchecks now use the literal address. The backend's happened to work
with `localhost`, but it was changed too rather than left depending on resolver
ordering.

## Configuration cleanup

Containerization surfaced that the datasource was the one part of
`application.yml` still hardcoded, and that a Spring profile existed holding
credentials. Both were addressed as a separate, isolated change once the images
were working.

### Datasource externalized

```yaml
# before
url: jdbc:postgresql://localhost:5432/trademesh
username: trademesh
password: trademesh

# after
url: ${DB_URL:jdbc:postgresql://localhost:5432/trademesh}
username: ${DB_USERNAME:trademesh}
password: ${DB_PASSWORD:trademesh}
```

This follows the `${VAR:default}` convention the file already used for Redis,
JWT, and CORS — bare `SCREAMING_SNAKE` names, no `SPRING_` prefix. The defaults
reproduce the previous values exactly, so local dev is unchanged with no env
vars set.

`driver-class-name` was deliberately left literal: the driver is a property of
what is on the classpath, not of the environment.

Note that Spring's relaxed binding means `SPRING_DATASOURCE_URL` would *also*
have worked without this change. The point of externalizing was consistency and
self-documentation — a ConfigMap listing `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`
says what the app needs; one relying on relaxed binding does not.

### The `docker` profile removed

The profile did **more than hold credentials**, which is worth recording since
"it was just hardcoded passwords" would be wrong. It set four things:

| Setting | Value |
|---|---|
| `spring.datasource.url` | `jdbc:postgresql://postgres:5432/trademesh` (hostname remap) |
| `spring.datasource.username` | `trademesh` |
| `spring.datasource.password` | `trademesh` |
| `spring.data.redis.host` | `redis` (hostname remap) |

It was removed anyway, because after the externalization above all four are
reachable through env vars, and **nothing in the repository activated it** — no
compose file, Dockerfile, or script set `SPRING_PROFILES_ACTIVE=docker`. The
application now has no profiles at all: one configuration file, every
environment-specific value supplied from outside.

Removing it also closes the trap it represented: had anything ever run with that
profile active in a real environment, it would have silently used the
hardcoded local credentials in preference to injected ones.

`docker-compose.apps.yml` was switched from `SPRING_DATASOURCE_*` to the new
`DB_*` names. Not cosmetic: `SPRING_DATASOURCE_URL` overrides the property
outright and never evaluates the `${DB_URL:...}` placeholder, so leaving it
would have meant the externalization was never actually exercised by the
container verification, and a typo in a placeholder name would have gone
unnoticed.

## Verification

**Images, built and run locally against the existing Compose Postgres/Redis:**

- Backend `/actuator/health` → `200` with `db: UP (PostgreSQL)` and
  `redis: UP (7.4.10)` — real connections to both.
- Both containers run as non-root: backend `uid=100(trademesh)`, frontend
  `uid=100(nextjs)`.
- Full authenticated flow through the containers, with the browser's `Origin`
  header on every call: register → login → resting SELL → matching BUY →
  **trade executed** → Redis projection correct → `/ws/info` reachable.
- CORS verified from the real page origin, including an authenticated preflight.

**Configuration change, verified four ways** — the first three host-run against
the same Postgres/Redis, the fourth in the rebuilt container:

| Case | Result |
|---|---|
| No env vars set | `status: UP`, `db: UP` — local-dev behaviour preserved exactly |
| `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` set | `status: UP`, `db: UP` — placeholders wired |
| `DB_USERNAME=wrong_user` | Startup fails: `FATAL: password authentication failed` |
| Rebuilt image under Compose | `status: UP`, `db: UP`, `redis: UP` |

The third case proves the most on its own: it shows the variable is genuinely
consulted rather than silently falling back to the default, which the first two
cases alone could not distinguish.

The fourth is decisive in a different way — inside the container nothing listens
on `localhost:5432`, so the old hardcoded default could not possibly have
produced `db: UP`. The container also logs
`No active profile set, falling back to 1 default profile: "default"`,
confirming the removed `docker` profile leaves nothing behind.

`mvn test` after the change: **88 tests, 0 failures, 0 errors, 2 skipped** —
unchanged from Phase 8.

### Image sizes

| Image | `docker images` | Actual layer content |
|---|---|---|
| `trademesh-backend:local` | 408 MB | ~276 MB |
| `trademesh-frontend:local` | 299 MB | ~226 MB |

The `docker images` figures are inflated by buildx attestation manifests. Real
content is a 165 MB Temurin JRE layer plus a 64 MB Spring Boot fat jar for the
backend, and the `node:22-alpine` base plus 51 MB of app for the frontend — of
which `node_modules` is 46 MB, pruned by standalone output to only the
dependencies actually reachable at runtime.

Neither is unexpectedly large for its stack. If the backend ever needs to be
smaller, the levers are a `jlink`-trimmed runtime or Spring Boot layered jars.

## Environment gotcha: image builds fail behind TLS interception

**Symptom.** `docker build` of the backend fails during `mvn package`:

```
PKIX path building failed: unable to find valid certification path to requested target
```

Dozens of artifacts report as `(absent)` even though the host builds fine.

**Cause.** The office network runs a TLS-intercepting firewall (FortiGate DPI).
A container on it sees a substituted certificate chain for Maven Central:

```
1 s:C = IN, ..., O = Galaxy Office Automation Pvt. Ltd., CN = FGT-DPI-CA
Verify return code: 19 (self-signed certificate in certificate chain)
```

The host JDK trusts that CA — which is why `mvn test` and host builds keep
working, and why this looks at first like a code problem. The container's
truststore does not.

**Diagnosis in one command.** This distinguishes it from a genuine build
failure immediately:

```
docker run --rm maven:3.9.9-eclipse-temurin-21 \
  sh -c 'echo | openssl s_client -connect repo.maven.apache.org:443 \
  -servername repo.maven.apache.org 2>/dev/null | grep -E "^ *[01] [si]:|Verify return code"'
```

- `CN = FGT-DPI-CA` / `Verify return code: 19` → intercepted, builds will fail.
- `O = Let's Encrypt` / `Verify return code: 0 (ok)` → clean, builds will work.

**Workaround for local builds: build off the intercepting network.** Switching
to a personal hotspot was confirmed sufficient — the chain verified clean and
the image built first time, with no change to the Dockerfile. Image *runs* are
unaffected either way; only builds that pull from Maven Central care.

**Stage 3 needs a real fix, not the workaround.** Jenkins + Kaniko build in
containers on the office network and will hit exactly this. The options are
importing the CA into the build image, pointing Maven at an internal mirror, or
exempting build agents from inspection. Not attempted here: baking a corporate
CA into an image has security implications that belong with the platform team,
and it is a Stage 3 concern rather than a containerization one.

---

# Stage 2 — Kubernetes manifests

Plain YAML, written to `k8s-manifests/apps/trademesh/base/` as a local staging
area. That folder is **not** a git operation and not the final home: the files
are destined to be copied into the shared GitOps repo under
`apps/trademesh/base/`, mirroring the file naming of that repo's existing
reference app (`apps/shopping-app/base/`), with `mongodb-*` replaced by
`postgres-*` and Redis added as a component the reference app does not have.

Nothing was applied to a cluster, pushed, or registered with Argo CD. Manifests
are reviewed and applied by hand first; the Argo CD Application registration is
Stage 3.

| File | Resource |
|---|---|
| `namespace.yaml` | Namespace `trademesh` |
| `backend-configmap.yaml` | ConfigMap `backend-config` |
| `backend-deployment.yaml` | Deployment `backend` (1 replica) |
| `backend-service.yaml` | Service `backend` (ClusterIP, 8080) |
| `frontend-deployment.yaml` | Deployment `frontend` (2 replicas) |
| `frontend-service.yaml` | Service `frontend` (ClusterIP, 3000) |
| `frontend-ingress.yaml` | Ingress `frontend` (host `trademesh.k8s.local`) |
| `postgres-deployment.yaml` | Deployment `postgres` (1 replica) |
| `postgres-pvc.yaml` | PersistentVolumeClaim `postgres-pvc` |
| `postgres-service.yaml` | Service `postgres` (ClusterIP, 5432) |
| `redis-deployment.yaml` | Deployment `redis` (1 replica) |
| `redis-service.yaml` | Service `redis` (ClusterIP, 6379) |

Resource names are short (`backend`, `postgres`) rather than prefixed
(`trademesh-backend`): the namespace already scopes them, and it makes the
in-cluster DNS names identical to the docker-compose service names — so
`jdbc:postgresql://postgres:5432/trademesh` is the same string locally and in
the cluster.

## The backend is pinned to one replica, loudly

`backend-deployment.yaml` carries a banner comment above `replicas: 1` that is
deliberately hard to skim past, because this is the one field in the whole
directory where a plausible-looking change silently corrupts trading state
rather than failing.

The matching engine's `OrderBook` is in-memory, in-process, and authoritative.
It is not shared between pods and there is no coordination layer, so two
replicas would each hold a divergent copy of the book, match against different
state, and write conflicting results to the same database — selling the same
liquidity twice with no error surfaced anywhere. And per Phase 8 the engine is
not yet safe even *within* one pod.

The comment states the two preconditions for ever raising it: intra-pod
concurrency fixed (with `ConcurrentOrderSubmissionTest` re-enabled and passing),
and a cross-pod ownership design — symbol sharding or leader election. Until
both hold, the app scales vertically only.

Two consequences follow from the same reasoning:

- **`strategy: Recreate` on the backend.** The default `RollingUpdate` briefly
  runs two backend pods at once, which is exactly the scenario the replica pin
  exists to prevent — the default would quietly undermine it on every deploy.
  A few seconds of downtime is the correct trade.
- **`strategy: Recreate` on Postgres too**, for an unrelated reason: a rolling
  update would start the new pod while the old one still holds a
  ReadWriteOnce volume, and the rollout simply wedges.

## Secrets stay out of the repository

No Secret manifest exists in this directory, by design — the GitOps repo is
shared, and a Secret committed there is a credential in git history forever.
`trademesh-secrets` is created out-of-band with `kubectl create secret` and must
carry exactly four keys, which become env vars verbatim via `envFrom`:
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`.

`postgres-deployment.yaml` reads `DB_USERNAME`/`DB_PASSWORD` from that *same*
Secret via `secretKeyRef` rather than declaring its own copy. One source of
truth means the database and the application cannot drift apart on the
password — a second Secret would make that failure possible and hard to spot.

Non-secret values live in `backend-config` (ConfigMap): `REDIS_HOST`,
`REDIS_PORT`, `CORS_ALLOWED_ORIGINS`, `JWT_EXPIRATION_MS`. The expiry is a
duration, not a credential, so it does not belong with the signing key.

## Storage: static NFS, and Redis has none

`postgres-pvc.yaml` follows the platform's static provisioning pattern — a
PersistentVolume created by hand, no dynamic StorageClass. Two details matter:

- `storageClassName: ""` is set explicitly, not omitted. Omitting it lets the
  cluster's default StorageClass provision something else entirely and ignore
  the hand-made NFS volume.
- `PGDATA` points at a *subdirectory* of the mount. Mounting at
  `/var/lib/postgresql/data` leaves the root non-empty (NFS metadata,
  `lost+found`) and `initdb` refuses to initialise into a non-empty directory.

The PV is cluster-scoped so it cannot live in a per-app folder; its template is
embedded as a comment inside the PVC file, which keeps it findable without it
being synced. **The claim stays `Pending` until that PV exists.**

Redis deliberately has **no** PVC. It holds a derived, rebuildable projection,
never a source of truth — `OrderBookWarmupRunner` reconstructs it from Postgres
on every startup, and the application already tolerates Redis being unreachable
(`MarketDataResilienceTest`). A volume would add a dependency and a failure mode
for nothing. The manifest says so inline, so it reads as a decision rather than
an oversight.

## One host, one origin

`frontend-ingress.yaml` serves everything from `http://trademesh.k8s.local`:
`/api` and `/ws` to the backend, `/` to the frontend. That makes the browser's
requests **same-origin**, so CORS never engages in normal use.
`CORS_ALLOWED_ORIGINS` is still set correctly for anything that arrives
cross-origin, and — more usefully — so the value is never a stale `localhost`
inherited from local development.

`ingressClassName: nginx` is used rather than the older
`kubernetes.io/ingress.class` annotation, which has been deprecated since
Kubernetes 1.18. Two annotations raise the proxy read/send timeouts to an hour:
ingress-nginx upgrades WebSocket connections automatically, but its default 60s
read timeout would still cut an idle STOMP connection roughly every minute.

## The frontend image must be built *for* the cluster

The trap Stage 1 documented has a direct consequence here, called out in a
banner comment in `frontend-deployment.yaml`: `NEXT_PUBLIC_*` values are
compiled into the JavaScript bundle at image-build time. **Setting them as env
vars on the container does nothing.**

An image built with the local defaults ships `http://localhost:8080` to the
browser, and every API call from a perfectly healthy-looking pod fails. The
`:v1` image must be built with
`--build-arg NEXT_PUBLIC_API_BASE_URL=http://trademesh.k8s.local` and the
matching `NEXT_PUBLIC_WS_URL`. Changing the hostname later means rebuilding and
re-tagging, not editing a manifest.

## Before these can be applied

| Placeholder | Where | Replace with |
|---|---|---|
| `<DOCKERHUB_USERNAME>` | `backend-deployment.yaml`, `frontend-deployment.yaml` | DockerHub username |
| `NFS_SERVER_PLACEHOLDER` | `postgres-pvc.yaml` (PV template comment) | NFS server address |
| `/srv/nfs/PLACEHOLDER/trademesh-postgres` | `postgres-pvc.yaml` (PV template comment) | Real export path |

Plus three out-of-band actions: create the PersistentVolume, create the
`trademesh-secrets` Secret, and build/push both images — the frontend one with
the build args above.

## Deviations from the reference app's file list

- **`namespace.yaml` added.** Every manifest hard-codes `namespace: trademesh`,
  so without it a fresh cluster fails with an error pointing at the Deployment
  rather than the missing namespace. Delete it if the platform creates
  namespaces out-of-band — nothing references it.
- **`backend-configmap.yaml` added.** The reference app may inline non-secret
  env vars. Split out here because these are the values most likely to differ
  per environment (a staging overlay patches this one file), and because it
  makes the secret/non-secret boundary visible at a glance.
- **`redis-*` added, `postgres-*` replaces `mongodb-*`** — the intended
  divergence, since TradeMesh's data tier is not the reference app's.

## Flagged for verification at apply time

`runAsNonRoot: true` with `runAsUser: 100` is set on both application pods, per
the platform's non-root requirement. The uid is stated numerically because both
Dockerfiles use `USER trademesh` / `USER nextjs` — *names*, which Kubernetes
cannot resolve to prove non-root, and would reject the pod for. The uid was
observed to be 100 for both images during Stage 1 verification, but if a pod
fails to start with a uid mismatch, this is the line. The durable fix is
`USER 100` in both Dockerfiles, after which the `runAsUser` line can be dropped.

## Documentation left deliberately stale

`docs/phases/phase-01-backend-setup.md` and
`docs/phases/phase-05-redis-market-data.md` both describe the `docker` profile,
which no longer exists. Those are phase logs — records of what shipped at the
time — and this project does not rewrite them retroactively; the living
documents (`architecture.md` and the design references) are the ones kept
current. The removal is recorded here and in `architecture.md` instead.

## Still out of scope

- Jenkins / Kaniko / Argo CD wiring, including the Argo CD Application
  registration for `apps/trademesh` (Stage 3)
- Applying the Stage 2 manifests to a cluster. They have not been run against a
  live API server, so admission-time validation, PSA/OPA policy, whether the
  `nginx` IngressClass exists, and image pulls are all still unproven — that
  happens at first apply
- Copying the manifests into the shared GitOps repo — done by hand, deliberately
  not a git operation from here
- Pushing images to DockerHub — everything above is local build and run only
- A permanent fix for the TLS-interception gotcha above. Building off the
  intercepting network is sufficient locally; CI needs a real answer in Stage 3
- The Phase 8 concurrency race, which remains unfixed and **should be resolved
  before any deployment exposes the engine to concurrent callers** — see
  `docs/matching-engine.md`
