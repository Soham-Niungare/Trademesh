# --- Dependencies stage ---
# Separated from the build so that a source-only change reuses this layer.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# --- Build stage ---
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# =============================================================================
# NEXT_PUBLIC_* VALUES ARE BAKED IN HERE, AT BUILD TIME. THEY ARE NOT RUNTIME
# CONFIGURATION.
#
# Next.js substitutes every `process.env.NEXT_PUBLIC_X` reference with a string
# literal while compiling the client bundle. By the time a container starts,
# these values are already inside the JavaScript shipped to the browser --
# setting a different NEXT_PUBLIC_* env var on a running container has no
# effect whatsoever. Changing any of them requires rebuilding the image.
#
# They must also be addresses reachable from THE BROWSER, not from inside the
# Docker network. The browser calls the backend directly (this app has no
# server-side proxy), so `http://backend:8080` would resolve only inside
# Compose and break every request from a real user. Use the address the user's
# browser will use -- http://localhost:8080 locally, the public API URL in a
# deployed environment.
#
# Build with e.g.:
#   docker build \
#     --build-arg NEXT_PUBLIC_API_BASE_URL=https://api.example.com \
#     --build-arg NEXT_PUBLIC_WS_URL=https://api.example.com/ws \
#     -t trademesh-frontend .
#
# The defaults below match frontend/.env.example, so a bare `docker build`
# produces a locally-working image.
# =============================================================================
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ARG NEXT_PUBLIC_WS_URL=http://localhost:8080/ws
ARG NEXT_PUBLIC_SYMBOLS=AAPL,MSFT,GOOG

ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_WS_URL=${NEXT_PUBLIC_WS_URL}
ENV NEXT_PUBLIC_SYMBOLS=${NEXT_PUBLIC_SYMBOLS}
ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build

# --- Runtime stage ---
FROM node:22-alpine
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN addgroup -S nodejs && adduser -S -G nodejs nextjs

# `output: "standalone"` (next.config.ts) emits a self-contained server plus
# only the node_modules actually reachable from the app, so the full dependency
# tree never enters the runtime image. static/ and public/ are not included in
# that bundle and must be copied alongside it.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs

EXPOSE 3000

# HOSTNAME=0.0.0.0 is required: the standalone server binds localhost by
# default, which would be unreachable from outside the container.
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# 127.0.0.1, not localhost. The standalone server binds the IPv4 wildcard
# (0.0.0.0:3000), while `localhost` resolves to IPv6 ::1 first inside this
# image -- where nothing is listening, so the probe gets connection-refused and
# the container is reported unhealthy while serving traffic perfectly well.
HEALTHCHECK --interval=15s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/login || exit 1

CMD ["node", "server.js"]
