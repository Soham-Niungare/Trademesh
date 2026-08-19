"use client";

import { Panel, PanelMessage } from "@/components/ui";
import { MAX_TRADES } from "@/lib/config";
import { formatPrice, formatQuantity, formatTimestamp } from "@/lib/format";
import type { ReceivedTrade } from "@/hooks/useMarketStream";

/**
 * Live trade tape.
 *
 * These come only from the websocket topic -- the backend exposes no REST route
 * for trade history (TradeRepository has the query; no controller publishes
 * it), so the tape necessarily starts empty on load and fills as trades happen.
 *
 * TradeExecutedMessage also carries no timestamp, so the time column is the
 * moment this client received the message, and is labelled as such rather than
 * being passed off as the execution time.
 */
export function TradeHistory({ symbol, trades }: { symbol: string; trades: ReceivedTrade[] }) {
  return (
    <Panel
      title="Recent trades"
      subtitle={`${symbol} · live, last ${MAX_TRADES}`}
      className="h-full"
    >
      {trades.length === 0 ? (
        <PanelMessage>
          No trades yet. This tape is live-only and fills as trades execute.
        </PanelMessage>
      ) : (
        <div className="flex flex-col py-2">
          <div className="grid grid-cols-3 px-4 pb-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">
            <span>Price</span>
            <span className="text-right">Quantity</span>
            <span className="text-right">Received</span>
          </div>
          {trades.map((trade) => (
            <div
              key={trade.key}
              className="grid grid-cols-3 px-4 py-1 text-xs tnum hover:bg-slate-800/40"
            >
              <span className="font-medium text-slate-200">{formatPrice(trade.price)}</span>
              <span className="text-right text-slate-300">
                {formatQuantity(trade.quantity)}
              </span>
              <span className="text-right text-slate-500">
                {formatTimestamp(new Date(trade.receivedAt).toISOString())}
              </span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}
