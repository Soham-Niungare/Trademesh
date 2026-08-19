"use client";

import { useCallback, useState } from "react";

import { ConnectionIndicator } from "@/components/ConnectionIndicator";
import { OpenOrdersList } from "@/components/OpenOrdersList";
import { OrderBookView, BestQuotes } from "@/components/OrderBookView";
import { OrderForm } from "@/components/OrderForm";
import { OrderHistoryList } from "@/components/OrderHistoryList";
import { SymbolSelector } from "@/components/SymbolSelector";
import { TradeHistory } from "@/components/TradeHistory";
import { useAuth } from "@/context/AuthContext";
import { useMarketStream } from "@/hooks/useMarketStream";
import { useOrders } from "@/hooks/useOrders";
import { DEFAULT_SYMBOL } from "@/lib/config";
import type { OrderSubmissionResponse } from "@/lib/types";

export default function DashboardPage() {
  const { token, username, logout } = useAuth();
  const [symbol, setSymbol] = useState(DEFAULT_SYMBOL);

  const {
    orders,
    openOrders,
    loading: ordersLoading,
    error: ordersError,
    cancellingIds,
    reload: reloadOrders,
    applyOrderUpdate,
    registerOrder,
    cancelOrder,
  } = useOrders(token);

  // The stream pushes ORDER_UPDATED straight into the orders state. Because the
  // topic is public and carries no userId, useOrders only applies updates whose
  // orderId it already recognises as this user's.
  const { connection, orderBook, bookLoading, bookError, trades, reloadBook } =
    useMarketStream(symbol, { onOrderUpdate: applyOrderUpdate });

  const handleSubmitted = useCallback(
    (result: OrderSubmissionResponse) => {
      registerOrder(result.order);
      // A marketable order also changes the resting state of the orders it
      // matched against, which this user may own too. The websocket carries
      // those updates, but a refresh keeps the list correct even if the socket
      // is currently down.
      if (result.trades.length > 0) reloadOrders();
    },
    [registerOrder, reloadOrders],
  );

  // DashboardLayout only renders children once authenticated, so token is set.
  if (!token) return null;

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-800 bg-slate-900/40">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <div className="flex items-center gap-4">
            <span className="text-sm font-bold tracking-tight text-slate-100">TradeMesh</span>
            <SymbolSelector symbol={symbol} onChange={setSymbol} />
          </div>

          <div className="flex items-center gap-3">
            <ConnectionIndicator state={connection} />
            {username ? (
              <span className="hidden text-xs text-slate-500 sm:inline">{username}</span>
            ) : null}
            <button
              type="button"
              onClick={logout}
              className="rounded-md border border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-300 transition-colors hover:border-slate-600 hover:text-slate-100"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
        <div className="grid gap-4 lg:grid-cols-[320px_minmax(0,1fr)_320px]">
          {/* Left: order entry */}
          <div className="order-2 lg:order-1">
            <OrderForm symbol={symbol} token={token} onSubmitted={handleSubmitted} />
          </div>

          {/* Middle: quotes + depth ladder */}
          <div className="order-1 flex flex-col gap-4 lg:order-2">
            <BestQuotes orderBook={orderBook} />
            <OrderBookView
              symbol={symbol}
              orderBook={orderBook}
              loading={bookLoading}
              error={bookError}
              onRetry={reloadBook}
            />
          </div>

          {/* Right: live tape */}
          <div className="order-3">
            <TradeHistory symbol={symbol} trades={trades} />
          </div>
        </div>

        <div className="mt-4 grid gap-4">
          <OpenOrdersList
            orders={openOrders}
            loading={ordersLoading}
            error={ordersError}
            cancellingIds={cancellingIds}
            onCancel={cancelOrder}
            onReload={reloadOrders}
          />
          <OrderHistoryList orders={orders} loading={ordersLoading} error={ordersError} />
        </div>
      </main>
    </div>
  );
}
