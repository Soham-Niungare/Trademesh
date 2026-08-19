"use client";

import { Panel, PanelLoading, PanelMessage, StatusPill } from "@/components/ui";
import { formatDateTime, formatPrice, formatQuantity, statusClasses, statusLabel } from "@/lib/format";
import type { OrderResponse } from "@/lib/types";

/**
 * Every order this user has ever placed, newest first -- the backend already
 * returns them in that order (findByUserIdOrderByCreatedAtDesc), so no client
 * sorting is applied on top.
 */
export function OrderHistoryList({
  orders,
  loading,
  error,
}: {
  orders: OrderResponse[];
  loading: boolean;
  error: string | null;
}) {
  return (
    <Panel title="Order history" subtitle={`${orders.length} total`}>
      {loading && orders.length === 0 ? (
        <PanelLoading label="Loading history…" />
      ) : error ? (
        <PanelMessage tone="error">{error}</PanelMessage>
      ) : orders.length === 0 ? (
        <PanelMessage>You haven&apos;t placed any orders yet.</PanelMessage>
      ) : (
        <div className="max-h-80 overflow-auto">
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-slate-900">
              <tr className="text-left text-[11px] uppercase tracking-wide text-slate-500">
                <th className="px-4 py-2 font-medium">Placed</th>
                <th className="px-4 py-2 font-medium">Side</th>
                <th className="px-4 py-2 font-medium">Symbol</th>
                <th className="px-4 py-2 font-medium">Type</th>
                <th className="px-4 py-2 text-right font-medium">Price</th>
                <th className="px-4 py-2 text-right font-medium">Filled</th>
                <th className="px-4 py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr key={order.id} className="border-t border-slate-800/70">
                  <td className="px-4 py-2 whitespace-nowrap text-slate-500 tnum">
                    {formatDateTime(order.createdAt)}
                  </td>
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
                  <td className="px-4 py-2 text-slate-400">{order.type}</td>
                  <td className="px-4 py-2 text-right text-slate-300 tnum">
                    {formatPrice(order.price)}
                  </td>
                  <td className="px-4 py-2 text-right text-slate-300 tnum">
                    {formatQuantity(order.quantity - order.remainingQuantity)}
                    <span className="text-slate-600"> / {formatQuantity(order.quantity)}</span>
                  </td>
                  <td className="px-4 py-2">
                    <StatusPill className={statusClasses(order.status)}>
                      {statusLabel(order.status)}
                    </StatusPill>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
