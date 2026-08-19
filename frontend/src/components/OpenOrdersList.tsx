"use client";

import { useState } from "react";

import { Panel, PanelLoading, PanelMessage, Spinner, StatusPill } from "@/components/ui";
import { ApiError } from "@/lib/api";
import { formatPrice, formatQuantity, statusClasses, statusLabel } from "@/lib/format";
import type { OrderResponse } from "@/lib/types";

export function OpenOrdersList({
  orders,
  loading,
  error,
  cancellingIds,
  onCancel,
  onReload,
}: {
  orders: OrderResponse[];
  loading: boolean;
  error: string | null;
  cancellingIds: ReadonlySet<string>;
  onCancel: (orderId: string) => Promise<void>;
  onReload: () => void;
}) {
  const [cancelError, setCancelError] = useState<string | null>(null);

  async function handleCancel(orderId: string) {
    setCancelError(null);
    try {
      await onCancel(orderId);
    } catch (caught) {
      if (caught instanceof ApiError) {
        // 409 means the order stopped resting between render and click -- it
        // filled, or was already cancelled. Refresh so the list stops showing
        // it as cancellable instead of just reporting the failure.
        if (caught.status === 409 || caught.status === 404) {
          onReload();
        }
        setCancelError(caught.message);
      } else {
        setCancelError("Could not cancel that order.");
      }
    }
  }

  return (
    <Panel
      title="Open orders"
      subtitle={`${orders.length} working`}
      action={
        <button
          type="button"
          onClick={onReload}
          className="shrink-0 text-xs text-slate-400 underline underline-offset-2 hover:text-slate-200"
        >
          Refresh
        </button>
      }
    >
      {cancelError ? (
        <p className="border-b border-rose-900/50 bg-rose-950/30 px-4 py-2 text-xs text-rose-200" role="alert">
          {cancelError}
        </p>
      ) : null}

      {loading && orders.length === 0 ? (
        <PanelLoading label="Loading orders…" />
      ) : error ? (
        <PanelMessage tone="error">{error}</PanelMessage>
      ) : orders.length === 0 ? (
        <PanelMessage>No working orders. Limit orders resting in the book appear here.</PanelMessage>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[11px] uppercase tracking-wide text-slate-500">
                <th className="px-4 py-2 font-medium">Side</th>
                <th className="px-4 py-2 font-medium">Symbol</th>
                <th className="px-4 py-2 text-right font-medium">Price</th>
                <th className="px-4 py-2 text-right font-medium">Remaining</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2" />
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => {
                const cancelling = cancellingIds.has(order.id);
                return (
                  <tr key={order.id} className="border-t border-slate-800/70">
                    <td className="px-4 py-2">
                      <span
                        className={
                          order.side === "BUY"
                            ? "font-semibold text-emerald-400"
                            : "font-semibold text-rose-400"
                        }
                      >
                        {order.side}
                      </span>
                    </td>
                    <td className="px-4 py-2 text-slate-300">{order.symbol}</td>
                    <td className="px-4 py-2 text-right text-slate-300 tnum">
                      {formatPrice(order.price)}
                    </td>
                    <td className="px-4 py-2 text-right text-slate-300 tnum">
                      {formatQuantity(order.remainingQuantity)}
                      <span className="text-slate-600"> / {formatQuantity(order.quantity)}</span>
                    </td>
                    <td className="px-4 py-2">
                      <StatusPill className={statusClasses(order.status)}>
                        {statusLabel(order.status)}
                      </StatusPill>
                    </td>
                    <td className="px-4 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => void handleCancel(order.id)}
                        disabled={cancelling}
                        className="inline-flex items-center gap-1.5 rounded border border-slate-700 px-2 py-1 text-[11px] font-medium text-slate-300 transition-colors hover:border-rose-700 hover:text-rose-300 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {cancelling ? <Spinner className="size-3" /> : null}
                        {cancelling ? "Cancelling…" : "Cancel"}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
