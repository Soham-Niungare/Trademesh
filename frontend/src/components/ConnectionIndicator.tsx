"use client";

import type { ConnectionState } from "@/hooks/useMarketStream";

const PRESENTATION: Record<
  ConnectionState,
  { label: string; dot: string; text: string; pulse: boolean }
> = {
  connected: {
    label: "Live",
    dot: "bg-emerald-400",
    text: "text-emerald-300",
    pulse: false,
  },
  connecting: {
    label: "Connecting…",
    dot: "bg-amber-400",
    text: "text-amber-300",
    pulse: true,
  },
  disconnected: {
    label: "Disconnected — retrying",
    dot: "bg-rose-400",
    text: "text-rose-300",
    pulse: true,
  },
};

/**
 * The "disconnected" label says "retrying" because that is literally true:
 * @stomp/stompjs reconnects on its own every 5s, so this is a transient state
 * the user does not need to act on.
 */
export function ConnectionIndicator({ state }: { state: ConnectionState }) {
  const { label, dot, text, pulse } = PRESENTATION[state];

  return (
    <span
      className="inline-flex items-center gap-2 rounded-full border border-slate-800 bg-slate-900/80 px-3 py-1 text-xs"
      role="status"
      aria-live="polite"
    >
      <span className={`size-2 rounded-full ${dot} ${pulse ? "animate-pulse" : ""}`} />
      <span className={text}>{label}</span>
    </span>
  );
}
