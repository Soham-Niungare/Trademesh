import type { OrderStatus } from "@/lib/types";

/** Prices render with 2 decimals; the demo symbols all trade in whole cents. */
export function formatPrice(price: number | null | undefined): string {
  if (price === null || price === undefined) return "—";
  return price.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

/**
 * Quantities are BigDecimals server-side but are whole numbers in practice.
 * Formatted without forced decimals so "100" doesn't render as "100.00", but
 * with up to 4 places preserved if a fractional quantity ever shows up.
 */
export function formatQuantity(quantity: number | null | undefined): string {
  if (quantity === null || quantity === undefined) return "—";
  return quantity.toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

export function formatTimestamp(isoString: string): string {
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function formatDateTime(isoString: string): string {
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function statusLabel(status: OrderStatus): string {
  return status === "PARTIALLY_FILLED" ? "PARTIAL" : status;
}

export function statusClasses(status: OrderStatus): string {
  switch (status) {
    case "OPEN":
      return "bg-sky-500/15 text-sky-300 ring-sky-500/30";
    case "PARTIALLY_FILLED":
      return "bg-amber-500/15 text-amber-300 ring-amber-500/30";
    case "FILLED":
      return "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30";
    case "CANCELLED":
      return "bg-slate-500/15 text-slate-400 ring-slate-500/30";
  }
}
