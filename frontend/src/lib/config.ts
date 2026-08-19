/**
 * Endpoint configuration, read from NEXT_PUBLIC_* env vars (see .env.example).
 *
 * These must be referenced as full literal `process.env.NEXT_PUBLIC_X`
 * expressions -- Next.js inlines them at build time by static substitution, so
 * a dynamic lookup like `process.env[key]` would silently produce undefined.
 *
 * Defaults match the backend's own local defaults (`mvn spring-boot:run` on
 * 8080), so a checkout with no .env.local still runs against a local backend.
 */

function trimTrailingSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

export const API_BASE_URL = trimTrailingSlash(
  process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080",
);

/**
 * The backend registers its STOMP endpoint with `.withSockJS()`, so this is an
 * http(s) URL rather than ws(s) -- SockJS performs its own transport
 * negotiation over HTTP and upgrades to a native WebSocket where it can.
 */
export const WS_URL =
  process.env.NEXT_PUBLIC_WS_URL || "http://localhost:8080/ws";

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
