"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { Client, IMessage, StompSubscription } from "@stomp/stompjs";

import { marketApi, ApiError } from "@/lib/api";
import { MAX_TRADES, WS_URL } from "@/lib/config";
import type {
  OrderBookResponse,
  OrderBookUpdatedMessage,
  OrderUpdatedMessage,
  TradeExecutedMessage,
} from "@/lib/types";

export type ConnectionState = "connecting" | "connected" | "disconnected";

/** A trade plus the time this client received it -- the payload carries no timestamp. */
export interface ReceivedTrade extends TradeExecutedMessage {
  receivedAt: number;
  key: string;
}

interface UseMarketStreamOptions {
  /**
   * Invoked for every ORDER_UPDATED message on the symbol's topic. These arrive
   * for all users' orders with `userId` stripped, so the consumer is
   * responsible for ignoring ids it doesn't recognise.
   */
  onOrderUpdate?: (message: OrderUpdatedMessage) => void;
}

interface UseMarketStreamResult {
  connection: ConnectionState;
  orderBook: OrderBookResponse | null;
  bookLoading: boolean;
  bookError: string | null;
  trades: ReceivedTrade[];
  reloadBook: () => void;
}

/** The websocket message omits bestBid/bestAsk; derive them as the REST DTO does. */
function toOrderBookResponse(message: OrderBookUpdatedMessage): OrderBookResponse {
  return {
    symbol: message.symbol,
    bestBid: message.bids[0] ?? null,
    bestAsk: message.asks[0] ?? null,
    bids: message.bids,
    asks: message.asks,
  };
}

/**
 * Owns the STOMP connection for one symbol: initial REST snapshot, live updates
 * on the three public topics, and reconnection.
 *
 * Reconnection is entirely @stomp/stompjs's own (`reconnectDelay`), not
 * hand-rolled. What this hook adds on top is the part the library can't do for
 * us: STOMP subscriptions do not survive a dropped connection, so all three are
 * (re-)created inside `onConnect`, which the client calls again after every
 * successful retry. The REST snapshot is re-fetched there too -- while the
 * socket was down, book updates were missed, so the locally-held book is stale
 * by an unknown amount and only a fresh snapshot can be trusted.
 */
export function useMarketStream(
  symbol: string,
  { onOrderUpdate }: UseMarketStreamOptions = {},
): UseMarketStreamResult {
  const [connection, setConnection] = useState<ConnectionState>("connecting");
  const [orderBook, setOrderBook] = useState<OrderBookResponse | null>(null);
  const [bookLoading, setBookLoading] = useState(true);
  const [bookError, setBookError] = useState<string | null>(null);
  const [trades, setTrades] = useState<ReceivedTrade[]>([]);

  // Held in a ref so a new callback identity on each render doesn't tear down
  // and rebuild the whole STOMP connection.
  const onOrderUpdateRef = useRef(onOrderUpdate);
  useEffect(() => {
    onOrderUpdateRef.current = onOrderUpdate;
  });

  /**
   * Discard the previous symbol's data the moment the symbol changes, during
   * render rather than in an effect: the old book and tape don't describe this
   * market at all, and clearing them in an effect would paint one frame of the
   * wrong symbol's prices under the new symbol's heading.
   */
  const [activeSymbol, setActiveSymbol] = useState(symbol);
  if (activeSymbol !== symbol) {
    setActiveSymbol(symbol);
    setOrderBook(null);
    setTrades([]);
    setBookError(null);
    setBookLoading(true);
    setConnection("connecting");
  }

  /**
   * Bumped every time a snapshot arrives over the socket. An in-flight REST
   * fetch records the value it started at and discards its own result if the
   * counter moved -- otherwise a slow HTTP response could overwrite a newer
   * snapshot that arrived over the socket while it was in flight.
   */
  const snapshotSeqRef = useRef(0);
  const mountedRef = useRef(true);

  /**
   * Deliberately does not flip `bookLoading` on; that is already true both
   * initially and after the symbol-change reset above, and setting it here
   * would mean updating state synchronously from the effect that calls this.
   * `reloadBook` -- which runs from user interaction -- sets it explicitly.
   */
  const loadBook = useCallback(async (targetSymbol: string) => {
    const startedAtSeq = snapshotSeqRef.current;
    try {
      const snapshot = await marketApi.orderBook(targetSymbol);
      if (!mountedRef.current) return;
      // A socket snapshot landed first, or the user switched symbols: drop this.
      if (snapshotSeqRef.current !== startedAtSeq || snapshot.symbol !== targetSymbol) return;
      setOrderBook(snapshot);
      setBookError(null);
    } catch (error) {
      if (!mountedRef.current) return;
      setBookError(
        error instanceof ApiError ? error.message : "Could not load the order book.",
      );
    } finally {
      if (mountedRef.current) setBookLoading(false);
    }
  }, []);

  const reloadBook = useCallback(() => {
    setBookLoading(true);
    void loadBook(symbol);
  }, [loadBook, symbol]);

  useEffect(() => {
    mountedRef.current = true;
    let cancelled = false;
    let client: Client | null = null;
    const subscriptions: StompSubscription[] = [];

    // Per-symbol view state is already cleared during render (see above); this
    // only resets the staleness counter that guards the REST/socket race.
    snapshotSeqRef.current = 0;

    // Every state update inside loadBook happens after an await, so none of it
    // is the synchronous cascade this rule guards against -- the rule does not
    // model the await boundary. Fetching the opening snapshot is precisely what
    // this effect exists to do.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadBook(symbol);

    async function connect() {
      // sockjs-client reads the CommonJS-era `global`. Assigned before the
      // dynamic import below so it exists by the time the module body runs --
      // a static import would be hoisted above this and defeat it.
      if (typeof window !== "undefined") {
        const globalScope = window as unknown as { global?: unknown };
        globalScope.global ??= window;
      }

      const [{ default: SockJS }, { Client: StompClient }] = await Promise.all([
        import("sockjs-client"),
        import("@stomp/stompjs"),
      ]);
      if (cancelled) return;

      client = new StompClient({
        // The backend endpoint is registered `.withSockJS()`, so the transport
        // must speak SockJS -- a raw WebSocket to /ws would not handshake.
        webSocketFactory: () => new SockJS(WS_URL),
        // Built-in reconnect: retry every 5s after a drop, indefinitely.
        reconnectDelay: 5000,
        // Detect a connection that has gone silent without a clean close.
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,

        beforeConnect: () => {
          if (!cancelled) setConnection("connecting");
        },

        onConnect: () => {
          if (cancelled || !client) return;
          setConnection("connected");

          // Subscriptions are created here, not once at setup: STOMP
          // subscriptions are per-connection and are lost on a drop, so they
          // must be re-established on every (re)connect.
          subscriptions.push(
            client.subscribe(`/topic/market/${symbol}/orderbook`, (message: IMessage) => {
              const payload = JSON.parse(message.body) as OrderBookUpdatedMessage;
              if (payload.symbol !== symbol) return;
              snapshotSeqRef.current += 1;
              setOrderBook(toOrderBookResponse(payload));
              setBookError(null);
              setBookLoading(false);
            }),
          );

          subscriptions.push(
            client.subscribe(`/topic/market/${symbol}/trades`, (message: IMessage) => {
              const payload = JSON.parse(message.body) as TradeExecutedMessage;
              if (payload.symbol !== symbol) return;
              const received: ReceivedTrade = {
                ...payload,
                receivedAt: Date.now(),
                // The payload has no trade id, and one order can produce several
                // trades in the same millisecond, so the key combines both order
                // ids with a random suffix rather than relying on time alone.
                key: `${payload.buyOrderId}:${payload.sellOrderId}:${Math.random().toString(36).slice(2)}`,
              };
              setTrades((current) => [received, ...current].slice(0, MAX_TRADES));
            }),
          );

          subscriptions.push(
            client.subscribe(`/topic/market/${symbol}/orders`, (message: IMessage) => {
              const payload = JSON.parse(message.body) as OrderUpdatedMessage;
              if (payload.symbol !== symbol) return;
              onOrderUpdateRef.current?.(payload);
            }),
          );

          // Re-sync after any (re)connect. On the first connect this is
          // redundant with the fetch above and simply confirms it; after a
          // reconnect it is the only way to recover the updates missed while
          // the socket was down.
          void loadBook(symbol);
        },

        onWebSocketClose: () => {
          // stompjs schedules its own retry; this only reflects the drop in the
          // UI. Subscription handles from the dead connection are discarded --
          // unsubscribing them would itself fail, and onConnect makes new ones.
          subscriptions.length = 0;
          if (!cancelled) setConnection("disconnected");
        },

        onStompError: (frame) => {
          console.error("STOMP protocol error", frame.headers["message"], frame.body);
          if (!cancelled) setConnection("disconnected");
        },
      });

      client.activate();
    }

    void connect();

    return () => {
      cancelled = true;
      mountedRef.current = false;
      // deactivate() unsubscribes, sends DISCONNECT, closes the socket, and --
      // importantly -- cancels any pending reconnect attempt, so navigating away
      // doesn't leave a client retrying forever in the background.
      void client?.deactivate();
      client = null;
    };
  }, [symbol, loadBook]);

  return { connection, orderBook, bookLoading, bookError, trades, reloadBook };
}
