/**
 * JWT persistence and (unverified) claim inspection.
 *
 * STORAGE DECISION: localStorage, not an httpOnly cookie.
 *
 * The honest tradeoff. An httpOnly cookie is strictly better against XSS --
 * script on the page cannot read it, so an injected script cannot exfiltrate
 * the token. localStorage has no such protection: any XSS on this origin can
 * read the token and replay it until it expires.
 *
 * It is still the right choice here, for three reasons specific to this project:
 *
 * 1. The backend reads `Authorization: Bearer <token>` and nothing else
 *    (JwtAuthenticationFilter). Switching to cookies is not a frontend change
 *    -- it needs the backend to set the cookie on login and to read it in the
 *    filter, which is out of scope for a frontend-only phase.
 * 2. The backend disables CSRF entirely (`csrf(AbstractHttpConfigurer::disable)`
 *    in SecurityConfig), which is correct *because* nothing is cookie-based.
 *    Cookie auth would make every authenticated route CSRF-attackable and
 *    require re-enabling CSRF protection -- a real security regression if the
 *    cookie half shipped without it.
 * 3. This is a portfolio project holding simulated positions, not money. The
 *    blast radius of a stolen token is a 1-hour session against fake orders.
 *
 * If this ever handled real value, the fix is not "obfuscate localStorage" --
 * it is httpOnly + SameSite cookies, CSRF tokens re-enabled, and short-lived
 * access tokens with a refresh endpoint. None of that exists backend-side yet.
 */

import type { OrderStatus } from "@/lib/types";

const TOKEN_KEY = "trademesh.jwt";

export function readToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    // Private-mode / storage-disabled browsers throw rather than return null.
    return null;
  }
}

export function writeToken(token: string): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Non-fatal: the in-memory auth context still holds the token for this tab,
    // the session just won't survive a reload.
  }
}

export function clearToken(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    // Nothing to do -- treated as already cleared.
  }
}

/** Claims issued by the backend's JwtService: subject is the user id. */
export interface JwtClaims {
  sub: string;
  username?: string;
  exp?: number;
  iat?: number;
}

function base64UrlDecode(segment: string): string {
  const base64 = segment.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64.padEnd(
    base64.length + ((4 - (base64.length % 4)) % 4),
    "=",
  );
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Reads the payload of a JWT WITHOUT verifying its signature.
 *
 * This is deliberately unverified and is only ever used for presentation and
 * for proactively expiring a session client-side -- never to decide whether a
 * request is allowed. A tampered token decoded here would still be rejected by
 * the backend, which is the only party holding the signing key. Returns null
 * for anything that isn't a well-formed three-segment token with a `sub`.
 */
export function decodeJwt(token: string): JwtClaims | null {
  const segments = token.split(".");
  if (segments.length !== 3) return null;
  try {
    const payload: unknown = JSON.parse(base64UrlDecode(segments[1]));
    if (
      typeof payload !== "object" ||
      payload === null ||
      typeof (payload as JwtClaims).sub !== "string"
    ) {
      return null;
    }
    return payload as JwtClaims;
  } catch {
    return null;
  }
}

/**
 * True if the token is malformed, or its `exp` has passed (with a small skew so
 * a token about to expire isn't treated as usable). A token with no `exp` is
 * treated as expired rather than eternal -- the backend always issues one, so
 * its absence means something is wrong with the token.
 */
export function isTokenExpired(token: string, skewSeconds = 30): boolean {
  const claims = decodeJwt(token);
  if (!claims || typeof claims.exp !== "number") return true;
  return claims.exp * 1000 <= Date.now() + skewSeconds * 1000;
}

/** Milliseconds until `exp`, or null if unknown. Used to schedule an auto-logout. */
export function millisUntilExpiry(token: string): number | null {
  const claims = decodeJwt(token);
  if (!claims || typeof claims.exp !== "number") return null;
  return claims.exp * 1000 - Date.now();
}

/** Statuses that mean an order is no longer working in the book. */
export const TERMINAL_STATUSES: readonly OrderStatus[] = ["FILLED", "CANCELLED"];
