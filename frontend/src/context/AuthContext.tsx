"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";

import { authApi, setUnauthorizedHandler } from "@/lib/api";
import {
  clearToken,
  decodeJwt,
  isTokenExpired,
  millisUntilExpiry,
  readToken,
  writeToken,
} from "@/lib/auth-storage";

/**
 * `loading` covers the first client render only: the token lives in
 * localStorage, which the server can't see, so the initial HTML is always
 * rendered as if signed out. Routing decisions wait for `loading` to resolve,
 * otherwise every protected page would bounce to /login for one frame.
 */
type AuthStatus = "loading" | "authenticated" | "anonymous";

/** Why the session ended, so /login can say something useful. */
export type LogoutReason = "expired" | "manual" | null;

interface AuthContextValue {
  status: AuthStatus;
  token: string | null;
  userId: string | null;
  username: string | null;
  logoutReason: LogoutReason;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<void>;
  logout: () => void;
  clearLogoutReason: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

interface Session {
  token: string;
  userId: string;
  username: string | null;
}

/**
 * Session and hydration flag are one state object so every transition is a
 * single setState -- notably the rehydrate-on-mount effect below, which would
 * otherwise cause two renders before the app knows whether it is signed in.
 */
interface AuthState {
  hydrated: boolean;
  session: Session | null;
}

function sessionFromToken(token: string): Session | null {
  const claims = decodeJwt(token);
  if (!claims) return null;
  return { token, userId: claims.sub, username: claims.username ?? null };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [state, setState] = useState<AuthState>({ hydrated: false, session: null });
  const [logoutReason, setLogoutReason] = useState<LogoutReason>(null);

  // Kept in a ref so the 401 handler and the expiry timer can end a session
  // without either of them being torn down and re-registered whenever the
  // session changes.
  const endSessionRef = useRef<(reason: LogoutReason) => void>(() => {});

  const endSession = useCallback(
    (reason: LogoutReason) => {
      clearToken();
      setState({ hydrated: true, session: null });
      setLogoutReason(reason);
      router.replace("/login");
    },
    [router],
  );

  useEffect(() => {
    endSessionRef.current = endSession;
  }, [endSession]);

  // Rehydrate from localStorage once, on mount.
  useEffect(() => {
    const stored = readToken();
    const restored =
      stored && !isTokenExpired(stored) ? sessionFromToken(stored) : null;
    // A token that is present but unusable (expired, or unparseable) is cleared
    // rather than left to fail on the next request.
    if (stored && !restored) clearToken();
    // localStorage is an external store with no server-side equivalent, so it
    // cannot be read during render without the server HTML and the client's
    // first render disagreeing. Reading it on mount and publishing the result
    // is the "subscribe to an external system" case the rule allows for; it
    // runs exactly once and settles into a single extra render.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState({ hydrated: true, session: restored });
  }, []);

  // Any 401 from any request ends the session. Reactive counterpart to the
  // proactive expiry timer below: a token can also stop being accepted for
  // reasons this client cannot see (a backend restart with a different
  // JWT_SECRET, clock skew), and those only ever surface as a 401.
  useEffect(() => {
    setUnauthorizedHandler(() => endSessionRef.current("expired"));
    return () => setUnauthorizedHandler(null);
  }, []);

  // Proactive expiry, so the session ends on schedule rather than at whatever
  // moment the user next clicks something and gets a 401. An already-expired
  // token still goes through the timer (clamped to 0) rather than being
  // handled inline, keeping this effect free of synchronous state updates.
  useEffect(() => {
    const session = state.session;
    if (!session) return;
    const remaining = millisUntilExpiry(session.token);
    if (remaining === null) return;
    const timer = window.setTimeout(
      () => endSessionRef.current("expired"),
      Math.max(0, remaining),
    );
    return () => window.clearTimeout(timer);
  }, [state.session]);

  const login = useCallback(async (usernameInput: string, password: string) => {
    const { token } = await authApi.login(usernameInput, password);
    const next = sessionFromToken(token);
    if (!next) {
      throw new Error("The server returned a token this client could not read.");
    }
    writeToken(token);
    setState({ hydrated: true, session: next });
    setLogoutReason(null);
  }, []);

  /**
   * Register returns only a userId -- no token (see AuthController) -- so this
   * logs in immediately afterwards to land the user on the dashboard rather
   * than bouncing them to a sign-in form they just filled out.
   */
  const register = useCallback(
    async (usernameInput: string, email: string, password: string) => {
      await authApi.register(usernameInput, email, password);
      await login(usernameInput, password);
    },
    [login],
  );

  const logout = useCallback(() => endSessionRef.current("manual"), []);
  const clearLogoutReason = useCallback(() => setLogoutReason(null), []);

  const status: AuthStatus = !state.hydrated
    ? "loading"
    : state.session
      ? "authenticated"
      : "anonymous";

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      token: state.session?.token ?? null,
      userId: state.session?.userId ?? null,
      username: state.session?.username ?? null,
      logoutReason,
      login,
      register,
      logout,
      clearLogoutReason,
    }),
    [status, state.session, logoutReason, login, register, logout, clearLogoutReason],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside an AuthProvider");
  }
  return context;
}
