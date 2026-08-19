"use client";

import { SYMBOLS } from "@/lib/config";

/**
 * The dashboard is a single-symbol view; this only changes which symbol that
 * is. Switching tears down the STOMP subscriptions for the old symbol and
 * re-subscribes for the new one (see useMarketStream's effect key), so only one
 * symbol's traffic is ever being consumed.
 */
export function SymbolSelector({
  symbol,
  onChange,
}: {
  symbol: string;
  onChange: (symbol: string) => void;
}) {
  // Nothing to choose between if only one symbol is configured.
  if (SYMBOLS.length <= 1) {
    return <span className="text-lg font-semibold text-slate-100">{symbol}</span>;
  }

  return (
    <label className="flex items-center gap-2">
      <span className="sr-only">Symbol</span>
      <select
        value={symbol}
        onChange={(event) => onChange(event.target.value)}
        className="rounded-md border border-slate-700 bg-slate-900 px-3 py-1.5 text-lg font-semibold text-slate-100 focus:border-sky-600 focus:outline-none focus:ring-1 focus:ring-sky-600"
      >
        {SYMBOLS.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </label>
  );
}
