import { API_BASE_URL } from "@/lib/config";
import type {
  ApiErrorResponse,
  LoginResponse,
  OrderBookResponse,
  OrderResponse,
  OrderSide,
  OrderSubmissionResponse,
  OrderType,
  RegisterResponse,
} from "@/lib/types";

/**
 * Any non-2xx response, plus the "never reached the server at all" case
 * (`status === 0`). `message` is the backend's own ErrorResponse.message where
 * one was returned, so validation and domain errors surface verbatim rather
 * than being re-worded here.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorResponse | null;

  constructor(status: number, message: string, body: ApiErrorResponse | null = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }

  /** True when the request never got a response -- backend down, or CORS blocked. */
  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

/**
 * Called whenever any request comes back 401, so the auth layer can clear the
 * stored token and route to /login instead of leaving the UI in a state where
 * every action silently fails. Registered by AuthProvider.
 */
type UnauthorizedHandler = () => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

interface RequestOptions {
  method?: "GET" | "POST" | "DELETE";
  body?: unknown;
  /** Bearer token, when the route requires one. */
  token?: string | null;
}

async function parseErrorBody(response: Response): Promise<ApiErrorResponse | null> {
  try {
    const parsed: unknown = await response.json();
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      typeof (parsed as ApiErrorResponse).message === "string"
    ) {
      return parsed as ApiErrorResponse;
    }
    return null;
  } catch {
    return null;
  }
}

async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, token } = options;

  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers["Authorization"] = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // fetch only rejects for network-level failures. A CORS rejection is
    // indistinguishable from "server unreachable" here by design -- the browser
    // withholds the response -- so the message names both, since a backend with
    // no CORS configuration is the far more likely cause during development.
    throw new ApiError(
      0,
      `Could not reach the TradeMesh API at ${API_BASE_URL}. Check that the backend is running, ` +
        `and that it allows cross-origin requests from this page (see frontend/README.md).`,
    );
  }

  if (response.status === 401) {
    unauthorizedHandler?.();
    const errorBody = await parseErrorBody(response);
    throw new ApiError(401, errorBody?.message ?? "Your session has expired. Please sign in again.", errorBody);
  }

  if (!response.ok) {
    const errorBody = await parseErrorBody(response);
    throw new ApiError(
      response.status,
      errorBody?.message ?? `Request failed with status ${response.status}`,
      errorBody,
    );
  }

  // 204, or any success with an empty body.
  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }

  return (await response.json()) as T;
}

// --- Auth ---

export const authApi = {
  register(username: string, email: string, password: string): Promise<RegisterResponse> {
    return apiFetch<RegisterResponse>("/api/auth/register", {
      method: "POST",
      body: { username, email, password },
    });
  },

  login(username: string, password: string): Promise<LoginResponse> {
    return apiFetch<LoginResponse>("/api/auth/login", {
      method: "POST",
      body: { username, password },
    });
  },
};

// --- Orders (all authenticated) ---

export interface CreateOrderPayload {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  /** Must be null for MARKET orders; required by the engine for LIMIT orders. */
  price: number | null;
  quantity: number;
}

export const ordersApi = {
  list(token: string): Promise<OrderResponse[]> {
    return apiFetch<OrderResponse[]>("/api/orders", { token });
  },

  get(token: string, orderId: string): Promise<OrderResponse> {
    return apiFetch<OrderResponse>(`/api/orders/${orderId}`, { token });
  },

  create(token: string, payload: CreateOrderPayload): Promise<OrderSubmissionResponse> {
    return apiFetch<OrderSubmissionResponse>("/api/orders", {
      method: "POST",
      token,
      body: payload,
    });
  },

  /** Returns the order in its final CANCELLED state; 409 if it wasn't resting. */
  cancel(token: string, orderId: string): Promise<OrderResponse> {
    return apiFetch<OrderResponse>(`/api/orders/${orderId}`, {
      method: "DELETE",
      token,
    });
  },
};

// --- Market data (public, no token) ---

export const marketApi = {
  orderBook(symbol: string): Promise<OrderBookResponse> {
    return apiFetch<OrderBookResponse>(
      `/api/market/${encodeURIComponent(symbol)}/orderbook`,
    );
  },
};
