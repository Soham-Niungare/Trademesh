/**
 * Endpoint configuration, read from NEXT_PUBLIC_* env vars (see .env.example).
 *
 * These must be referenced as full literal `process.env.NEXT_PUBLIC_X`
 * expressions -- Next.js inlines them at build time by static substitution, so
 * a dynamic lookup like `process.env[key]` would silently produce undefined.
 *
 * The NEXT_PUBLIC_* vars are OVERRIDES, not the primary configuration path.
 * Leaving them unset gives the local-dev defaults below, which match the
 * backend's own local defaults (`mvn spring-boot:run` on 8080) -- so a
 * checkout with no .env.local runs against a local backend with no setup.
 *
 * Container builds do not rely on these defaults: frontend/Dockerfile declares
 * ARG defaults carrying the cluster (same-origin) values, and the build args
 * win over what is written here. Deployment-specific hostnames therefore live
 * in the Dockerfile, never in this file.
 */

function trimTrailingSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

/**
 * Sentinel meaning "same origin as this page", accepted by both
 * NEXT_PUBLIC_API_BASE_URL and NEXT_PUBLIC_WS_URL.
 *
 * Why a sentinel rather than just setting the var to an empty string, which is
 * what same-origin actually resolves to below: Next.js inlines "" literally,
 * and `process.env.X || "http://localhost:8080"` treats it as falsy -- so an
 * empty build arg would silently produce a cluster image pinned to
 * localhost:8080. The sentinel is a value the `||` cannot swallow.
 */
const SAME_ORIGIN = "same-origin";

/**
 * Prefix for every REST call. In same-origin mode this is deliberately the
 * empty string: the call sites in api.ts already start their paths with
 * `/api` (matching the backend's own @RequestMapping and the Ingress's /api
 * rule), so an empty base yields a relative URL like `/api/auth/login` that
 * the browser resolves against the page's own origin -- exactly what a single
 * Ingress serving both / and /api needs.
 *
 * Note that anything non-empty here is concatenated with those paths as-is.
 * A base of `/api` would produce `/api/api/auth/login`, which never reaches a
 * controller. The base names the ORIGIN; the paths name the route.
 */
const configuredApi =
  process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

export const API_BASE_URL =
  configuredApi === SAME_ORIGIN ? "" : trimTrailingSlash(configuredApi);

/**
 * The backend registers its STOMP endpoint with `.withSockJS()`, so this is an
 * http(s) URL rather than ws(s) -- SockJS performs its own transport
 * negotiation over HTTP and upgrades to a native WebSocket where it can.
 *
 * The "same-origin" sentinel exists because those two facts collide: SockJS
 * requires an absolute URL, but in a single-origin deployment (one Ingress
 * routing /ws to the backend and / to this app) the correct absolute URL is
 * only knowable in the browser. Baking a hostname in at build time is what
 * this sentinel avoids -- the origin is read from window.location instead.
 *
 * A function, not a const, deliberately: `window` does not exist during App
 * Router server rendering, so reading window.location at module load would
 * break `npm run build`. Every caller is already client-side and lazy.
 */
export function getWsUrl(): string {
  const configured =
    process.env.NEXT_PUBLIC_WS_URL || "http://localhost:8080/ws";
  if (configured !== SAME_ORIGIN) return trimTrailingSlash(configured);
  if (typeof window === "undefined") {
    throw new Error(
      "getWsUrl() must be called from the browser in same-origin mode; it " +
      "derives the origin from window.location and has no SSR value."
    );
  }
  return `${window.location.origin}/ws`;
}

/**
 * Purely a frontend convenience list. The backend has no registered set of
 * symbols -- any string is valid, and one that has never traded just returns an
 * empty book rather than a 404 (see MarketController) -- so this list only
 * decides what the symbol switcher offers.
 */
export const SYMBOLS: string[] = (process.env.NEXT_PUBLIC_SYMBOLS || "AAPL")
  .split(",")
  .map((symbol) => symbol.trim().toUpperCase())
  .filter((symbol) => symbol.length > 0);

export const DEFAULT_SYMBOL = SYMBOLS[0] ?? "AAPL";

/** How many trades the dashboard's live tape keeps before dropping the oldest. */
export const MAX_TRADES = 20;
