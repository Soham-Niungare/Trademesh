"use client";

import { useState, type FormEvent } from "react";

import {
  ErrorBanner,
  Field,
  Panel,
  Spinner,
  SuccessBanner,
  inputClasses,
  primaryButtonClasses,
} from "@/components/ui";
import { ApiError, ordersApi } from "@/lib/api";
import { formatPrice, formatQuantity } from "@/lib/format";
import type { OrderSide, OrderSubmissionResponse, OrderType } from "@/lib/types";

interface FieldErrors {
  price?: string;
  quantity?: string;
}

export function OrderForm({
  symbol,
  token,
  onSubmitted,
}: {
  symbol: string;
  token: string;
  onSubmitted: (result: OrderSubmissionResponse) => void;
}) {
  const [side, setSide] = useState<OrderSide>("BUY");
  const [type, setType] = useState<OrderType>("LIMIT");
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<OrderSubmissionResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);

  /**
   * Mirrors the backend's own rules rather than inventing stricter ones:
   * quantity is @NotNull @Positive on CreateOrderRequest, and EngineOrder throws
   * "price is required for LIMIT orders". Client-side checks are for fast
   * feedback only -- the server remains the authority, and its message is what
   * gets displayed if something slips through.
   */
  function validate(): FieldErrors {
    const errors: FieldErrors = {};

    const parsedQuantity = Number(quantity);
    if (quantity.trim() === "") {
      errors.quantity = "Quantity is required.";
    } else if (!Number.isFinite(parsedQuantity) || parsedQuantity <= 0) {
      errors.quantity = "Quantity must be greater than zero.";
    }

    if (type === "LIMIT") {
      const parsedPrice = Number(price);
      if (price.trim() === "") {
        errors.price = "Price is required for LIMIT orders.";
      } else if (!Number.isFinite(parsedPrice) || parsedPrice <= 0) {
        errors.price = "Price must be greater than zero.";
      }
    }

    return errors;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitError(null);
    setLastResult(null);

    const errors = validate();
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setSubmitting(true);
    try {
      const result = await ordersApi.create(token, {
        symbol,
        side,
        type,
        // Explicitly null for MARKET: the engine keys off a null price, and
        // sending a stale value from a previously-typed LIMIT price would be
        // silently carried onto an order that never uses it.
        price: type === "MARKET" ? null : Number(price),
        quantity: Number(quantity),
      });
      setLastResult(result);
      setQuantity("");
      if (type === "MARKET") setPrice("");
      onSubmitted(result);
    } catch (caught) {
      // 401 is handled globally by the auth layer (session cleared, redirect),
      // but the message is still shown in case the redirect is in flight.
      setSubmitError(
        caught instanceof ApiError
          ? caught.message
          : "Something went wrong submitting this order.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  const sideButtonBase =
    "flex-1 rounded-md px-3 py-2 text-sm font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-offset-slate-950";

  return (
    <Panel title="Place order" subtitle={symbol}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4 p-4" noValidate>
        <div className="flex gap-2" role="group" aria-label="Order side">
          <button
            type="button"
            onClick={() => setSide("BUY")}
            aria-pressed={side === "BUY"}
            className={`${sideButtonBase} focus:ring-emerald-500 ${
              side === "BUY"
                ? "bg-emerald-600 text-white"
                : "bg-slate-800 text-slate-300 hover:bg-slate-700"
            }`}
          >
            Buy
          </button>
          <button
            type="button"
            onClick={() => setSide("SELL")}
            aria-pressed={side === "SELL"}
            className={`${sideButtonBase} focus:ring-rose-500 ${
              side === "SELL"
                ? "bg-rose-600 text-white"
                : "bg-slate-800 text-slate-300 hover:bg-slate-700"
            }`}
          >
            Sell
          </button>
        </div>

        <Field label="Symbol" htmlFor="order-symbol">
          <input
            id="order-symbol"
            value={symbol}
            readOnly
            aria-describedby="order-symbol-hint"
            className={`${inputClasses} cursor-not-allowed text-slate-400`}
          />
          <p id="order-symbol-hint" className="text-xs text-slate-600">
            Follows the dashboard symbol.
          </p>
        </Field>

        <Field label="Type" htmlFor="order-type">
          <select
            id="order-type"
            value={type}
            onChange={(event) => {
              const nextType = event.target.value as OrderType;
              setType(nextType);
              // Drop a price-required error the moment it stops applying.
              if (nextType === "MARKET") {
                setFieldErrors((current) => ({ ...current, price: undefined }));
              }
            }}
            className={inputClasses}
          >
            <option value="LIMIT">Limit</option>
            <option value="MARKET">Market</option>
          </select>
        </Field>

        {/* Hidden entirely for MARKET -- the backend ignores price on those. */}
        {type === "LIMIT" ? (
          <Field label="Price" htmlFor="order-price" error={fieldErrors.price}>
            <input
              id="order-price"
              type="number"
              inputMode="decimal"
              step="0.01"
              min="0"
              value={price}
              onChange={(event) => setPrice(event.target.value)}
              placeholder="0.00"
              className={inputClasses}
            />
          </Field>
        ) : null}

        <Field label="Quantity" htmlFor="order-quantity" error={fieldErrors.quantity}>
          <input
            id="order-quantity"
            type="number"
            inputMode="decimal"
            step="1"
            min="0"
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            placeholder="0"
            className={inputClasses}
          />
        </Field>

        {submitError ? <ErrorBanner message={submitError} /> : null}

        {lastResult ? (
          <SuccessBanner>
            {lastResult.trades.length > 0 ? (
              <>
                Order {lastResult.order.status === "FILLED" ? "filled" : "partially filled"} —{" "}
                {lastResult.trades.length} trade
                {lastResult.trades.length === 1 ? "" : "s"} at{" "}
                {formatPrice(lastResult.trades[0].price)}
                {lastResult.trades.length > 1 ? " and other levels" : ""}.
              </>
            ) : (
              <>
                Order accepted —{" "}
                {lastResult.order.type === "LIMIT"
                  ? `resting for ${formatQuantity(lastResult.order.remainingQuantity)}.`
                  : "no liquidity available, nothing filled."}
              </>
            )}
          </SuccessBanner>
        ) : null}

        <button type="submit" disabled={submitting} className={primaryButtonClasses}>
          {submitting ? <Spinner /> : null}
          {submitting ? "Submitting…" : `${side === "BUY" ? "Buy" : "Sell"} ${symbol}`}
        </button>
      </form>
    </Panel>
  );
}
