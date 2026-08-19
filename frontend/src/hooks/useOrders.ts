"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { ApiError, ordersApi } from "@/lib/api";
import type { OrderResponse, OrderUpdatedMessage } from "@/lib/types";

interface UseOrdersResult {
  /** Full history, most recent first (the backend already sorts it this way). */
  orders: OrderResponse[];
  /** Orders still working in the book -- the only ones that can be cancelled. */
  openOrders: OrderResponse[];
  loading: boolean;
  error: string | null;
  cancellingIds: ReadonlySet<string>;
  reload: () => void;
  /** Applies an ORDER_UPDATED websocket message, if it refers to a known order. */
  applyOrderUpdate: (message: OrderUpdatedMessage) => void;
  /** Adds the order returned by a successful POST /api/orders. */
  registerOrder: (order: OrderResponse) => void;
  cancelOrder: (orderId: string) => Promise<void>;
}

/**
 * An order is "open" only if it is genuinely resting in the book.
 *
 * Status alone is not enough. Per docs/matching-engine.md, the engine reuses
 * OPEN to mean "nothing filled" for a MARKET order that never rests -- so an
 * unfilled MARKET order is persisted as OPEN despite being gone. Listing it as
 * cancellable would offer a Cancel button that can only ever return 409, which
 * is exactly the trap that doc warns callers about: check the type and the
 * remaining quantity, don't lean on OPEN alone.
 */
function isWorking(order: OrderResponse): boolean {
  return (
    order.type === "LIMIT" &&
    (order.status === "OPEN" || order.status === "PARTIALLY_FILLED") &&
    order.remainingQuantity > 0
  );
}

export function useOrders(token: string | null): UseOrdersResult {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancellingIds, setCancellingIds] = useState<ReadonlySet<string>>(new Set());

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  /**
   * Does not flip `loading` on itself -- it starts true and the mount effect
   * below calls this directly, so setting it here would be a synchronous state
   * update driven from an effect. `reload`, which runs from user interaction,
   * sets it explicitly.
   */
  const load = useCallback(async () => {
    if (!token) return;
    try {
      const result = await ordersApi.list(token);
      if (!mountedRef.current) return;
      setOrders(result);
      setError(null);
    } catch (caught) {
      if (!mountedRef.current) return;
      // A 401 is already being handled globally (the auth layer clears the
      // session and redirects), so it isn't surfaced as a panel error here.
      if (caught instanceof ApiError && caught.status === 401) return;
      setError(
        caught instanceof ApiError ? caught.message : "Could not load your orders.",
      );
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    // As in useMarketStream: every state update inside load runs after an
    // await, so this is not the synchronous cascade the rule guards against.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  /**
   * ORDER_UPDATED is broadcast on a public topic with `userId` stripped, so
   * these messages arrive for every user's orders and carry nothing to
   * attribute them by. Matching on `orderId` against orders already known to be
   * this user's is therefore the only correct filter -- an unrecognised id
   * belongs to somebody else and is ignored rather than added.
   */
  const applyOrderUpdate = useCallback((message: OrderUpdatedMessage) => {
    setOrders((current) => {
      const index = current.findIndex((order) => order.id === message.orderId);
      if (index === -1) return current;

      const existing = current[index];
      if (
        existing.status === message.status &&
        existing.remainingQuantity === message.remainingQuantity
      ) {
        return current;
      }

      const next = [...current];
      next[index] = {
        ...existing,
        status: message.status,
        remainingQuantity: message.remainingQuantity,
      };
      return next;
    });
  }, []);

  /**
   * The POST response is read back from Postgres after commit, so it is already
   * the order's settled state. If a websocket update for it happened to arrive
   * before this call (the event fires post-commit, ahead of the HTTP response),
   * that update was dropped as unrecognised -- `reload` is the recovery path
   * for that narrow race.
   */
  const registerOrder = useCallback((order: OrderResponse) => {
    setOrders((current) =>
      current.some((existing) => existing.id === order.id)
        ? current.map((existing) => (existing.id === order.id ? order : existing))
        : [order, ...current],
    );
  }, []);

  const cancelOrder = useCallback(
    async (orderId: string) => {
      if (!token) return;
      setCancellingIds((current) => new Set(current).add(orderId));
      try {
        const cancelled = await ordersApi.cancel(token, orderId);
        if (mountedRef.current) registerOrder(cancelled);
      } finally {
        if (mountedRef.current) {
          setCancellingIds((current) => {
            const next = new Set(current);
            next.delete(orderId);
            return next;
          });
        }
      }
    },
    [token, registerOrder],
  );

  const openOrders = useMemo(() => orders.filter(isWorking), [orders]);

  const reload = useCallback(() => {
    setLoading(true);
    void load();
  }, [load]);

  return {
    orders,
    openOrders,
    loading,
    error,
    cancellingIds,
    reload,
    applyOrderUpdate,
    registerOrder,
    cancelOrder,
  };
}
