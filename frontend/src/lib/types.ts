/**
 * TypeScript mirrors of the backend's wire types. Field names and nullability
 * follow the Java records exactly -- see backend/src/main/java/com/trademesh/backend/
 * {controller,websocket,web}.
 *
 * Numeric fields are Java `BigDecimal`s, which Jackson serializes as JSON
 * numbers, so they arrive here as `number`. Instants serialize as ISO-8601
 * strings (Spring Boot disables WRITE_DATES_AS_TIMESTAMPS by default).
 */

export type OrderSide = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type OrderStatus = "OPEN" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED";

// --- REST: auth ---

export interface RegisterResponse {
  userId: string;
}

/** `expiresIn` is in SECONDS (JwtService.expirySeconds), not milliseconds. */
export interface LoginResponse {
  token: string;
  expiresIn: number;
}

// --- REST: orders ---

export interface OrderResponse {
  id: string;
  userId: string;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  /** null for MARKET orders. */
  price: number | null;
  quantity: number;
  remainingQuantity: number;
  status: OrderStatus;
  createdAt: string;
}

export interface TradeResponse {
  id: string;
  symbol: string;
  buyOrderId: string;
  sellOrderId: string;
  price: number;
  quantity: number;
  executedAt: string;
}

export interface OrderSubmissionResponse {
  order: OrderResponse;
  trades: TradeResponse[];
}

// --- REST: market data ---

export interface PriceLevelResponse {
  price: number;
  totalQuantity: number;
}

/** `bestBid`/`bestAsk` are null when that side is empty -- present, not omitted. */
export interface OrderBookResponse {
  symbol: string;
  bestBid: PriceLevelResponse | null;
  bestAsk: PriceLevelResponse | null;
  bids: PriceLevelResponse[];
  asks: PriceLevelResponse[];
}

// --- WebSocket payloads ---

/** `/topic/market/{symbol}/trades` -- one message per trade. No timestamp field. */
export interface TradeExecutedMessage {
  symbol: string;
  buyOrderId: string;
  sellOrderId: string;
  price: number;
  quantity: number;
}

/**
 * `/topic/market/{symbol}/orders` -- one message per order whose state changed.
 *
 * Deliberately carries no `userId`: the topic is public, and the backend strips
 * it to avoid leaking who placed an order (see docs/websocket.md). The practical
 * consequence for this client is that these messages arrive for EVERY user's
 * orders and cannot be attributed -- so they're only applied to orders already
 * known to belong to the signed-in user, matched by `orderId`.
 */
export interface OrderUpdatedMessage {
  orderId: string;
  symbol: string;
  status: OrderStatus;
  remainingQuantity: number;
}

/**
 * `/topic/market/{symbol}/orderbook` -- the full snapshot after any change.
 *
 * A strict subset of `OrderBookResponse`: no `bestBid`/`bestAsk`, since the
 * backend's websocket package deliberately doesn't reuse the controller
 * package's DTOs. They're derived from `bids[0]`/`asks[0]` here, exactly as
 * `OrderBookResponse.from` does server-side.
 */
export interface OrderBookUpdatedMessage {
  symbol: string;
  bids: PriceLevelResponse[];
  asks: PriceLevelResponse[];
}

// --- Errors ---

/** The single error shape every failing backend route returns (web/ErrorResponse). */
export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
}
