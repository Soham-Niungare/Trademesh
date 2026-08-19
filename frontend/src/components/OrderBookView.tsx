"use client";

import { Panel, PanelLoading, PanelMessage } from "@/components/ui";
import { formatPrice, formatQuantity } from "@/lib/format";
import type { OrderBookResponse, PriceLevelResponse } from "@/lib/types";

/**
 * Conventional depth ladder: asks descending from the top so the best (lowest)
 * ask sits directly above the spread, bids below it descending so the best
 * (highest) bid sits directly under. The two best prices are therefore always
 * adjacent, which is how the spread is read.
 */
function LevelRow({
  level,
  maxQuantity,
  side,
  isBest,
}: {
  level: PriceLevelResponse;
  maxQuantity: number;
  side: "bid" | "ask";
  isBest: boolean;
}) {
  const depthPercent = maxQuantity > 0 ? (level.totalQuantity / maxQuantity) * 100 : 0;
  const priceColor = side === "bid" ? "text-emerald-400" : "text-rose-400";
  const depthColor = side === "bid" ? "bg-emerald-500/10" : "bg-rose-500/10";

  return (
    <div className="relative grid grid-cols-2 px-4 py-1 text-xs tnum">
      <div
        aria-hidden
        className={`absolute inset-y-0 right-0 ${depthColor}`}
        style={{ width: `${depthPercent}%` }}
      />
      <span className={`relative font-medium ${priceColor} ${isBest ? "font-bold" : ""}`}>
        {formatPrice(level.price)}
      </span>
      <span className="relative text-right text-slate-300">
        {formatQuantity(level.totalQuantity)}
      </span>
    </div>
  );
}

export function OrderBookView({
  symbol,
  orderBook,
  loading,
  error,
  onRetry,
}: {
  symbol: string;
  orderBook: OrderBookResponse | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}) {
  const bids = orderBook?.bids ?? [];
  const asks = orderBook?.asks ?? [];

  // One scale across both sides so bar widths are comparable bid-to-ask.
  const maxQuantity = Math.max(
    0,
    ...bids.map((level) => level.totalQuantity),
    ...asks.map((level) => level.totalQuantity),
  );

  const bestBid = orderBook?.bestBid ?? null;
  const bestAsk = orderBook?.bestAsk ?? null;
  const spread =
    bestBid && bestAsk ? bestAsk.price - bestBid.price : null;

  return (
    <Panel title="Order book" subtitle={symbol} className="h-full">
      {loading && !orderBook ? (
        <PanelLoading label="Loading order book…" />
      ) : error && !orderBook ? (
        <div className="px-4 py-6 text-center">
          <p className="text-sm text-rose-300">{error}</p>
          <button
            type="button"
            onClick={onRetry}
            className="mt-2 text-xs text-sky-400 underline underline-offset-2 hover:text-sky-300"
          >
            Retry
          </button>
        </div>
      ) : bids.length === 0 && asks.length === 0 ? (
        <PanelMessage>No resting orders for {symbol}.</PanelMessage>
      ) : (
        <div className="flex flex-col py-2">
          <div className="grid grid-cols-2 px-4 pb-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">
            <span>Price</span>
            <span className="text-right">Quantity</span>
          </div>

          <div className="flex flex-col-reverse">
            {asks.map((level) => (
              <LevelRow
                key={`ask-${level.price}`}
                level={level}
                maxQuantity={maxQuantity}
                side="ask"
                isBest={level.price === bestAsk?.price}
              />
            ))}
          </div>

          <div className="my-1 flex items-center justify-between border-y border-slate-800 bg-slate-950/40 px-4 py-1.5 text-xs tnum">
            <span className="text-slate-500">Spread</span>
            <span className="text-slate-300">
              {spread === null ? "—" : formatPrice(spread)}
            </span>
          </div>

          <div className="flex flex-col">
            {bids.map((level) => (
              <LevelRow
                key={`bid-${level.price}`}
                level={level}
                maxQuantity={maxQuantity}
                side="bid"
                isBest={level.price === bestBid?.price}
              />
            ))}
          </div>
        </div>
      )}
    </Panel>
  );
}

/** Best bid / best ask summary, shown above the ladder. */
export function BestQuotes({ orderBook }: { orderBook: OrderBookResponse | null }) {
  const bestBid = orderBook?.bestBid ?? null;
  const bestAsk = orderBook?.bestAsk ?? null;

  return (
    <div className="grid grid-cols-2 gap-3">
      <div className="rounded-lg border border-slate-800 bg-slate-900/60 px-4 py-3">
        <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
          Best bid
        </p>
        <p className="mt-1 text-xl font-semibold text-emerald-400 tnum">
          {bestBid ? formatPrice(bestBid.price) : "—"}
        </p>
        <p className="text-xs text-slate-500 tnum">
          {bestBid ? `${formatQuantity(bestBid.totalQuantity)} qty` : "no bids"}
        </p>
      </div>
      <div className="rounded-lg border border-slate-800 bg-slate-900/60 px-4 py-3">
        <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
          Best ask
        </p>
        <p className="mt-1 text-xl font-semibold text-rose-400 tnum">
          {bestAsk ? formatPrice(bestAsk.price) : "—"}
        </p>
        <p className="text-xs text-slate-500 tnum">
          {bestAsk ? `${formatQuantity(bestAsk.totalQuantity)} qty` : "no asks"}
        </p>
      </div>
    </div>
  );
}
