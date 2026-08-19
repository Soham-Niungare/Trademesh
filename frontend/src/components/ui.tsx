import type { ReactNode } from "react";

/** Card shell used by every dashboard panel, with an optional header slot. */
export function Panel({
  title,
  subtitle,
  action,
  children,
  className = "",
}: {
  title: string;
  subtitle?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`flex flex-col rounded-lg border border-slate-800 bg-slate-900/60 ${className}`}
    >
      <header className="flex items-baseline justify-between gap-3 border-b border-slate-800 px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold tracking-wide text-slate-200">{title}</h2>
          {subtitle ? (
            <p className="mt-0.5 truncate text-xs text-slate-500">{subtitle}</p>
          ) : null}
        </div>
        {action}
      </header>
      <div className="min-h-0 flex-1">{children}</div>
    </section>
  );
}

export function Spinner({ className = "" }: { className?: string }) {
  return (
    <span
      role="status"
      aria-label="Loading"
      className={`inline-block size-4 animate-spin rounded-full border-2 border-slate-600 border-t-slate-200 ${className}`}
    />
  );
}

export function PanelMessage({
  children,
  tone = "muted",
}: {
  children: ReactNode;
  tone?: "muted" | "error";
}) {
  const toneClass = tone === "error" ? "text-rose-300" : "text-slate-500";
  return (
    <p className={`px-4 py-6 text-center text-sm ${toneClass}`}>{children}</p>
  );
}

export function PanelLoading({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 px-4 py-6 text-sm text-slate-500">
      <Spinner />
      <span>{label}</span>
    </div>
  );
}

/** Inline error banner for forms and page-level failures. */
export function ErrorBanner({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div
      role="alert"
      className="flex items-start justify-between gap-3 rounded-md border border-rose-900/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-200"
    >
      <span className="min-w-0">{message}</span>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="shrink-0 rounded px-2 py-0.5 text-xs font-medium text-rose-100 underline underline-offset-2 hover:bg-rose-900/40"
        >
          Retry
        </button>
      ) : null}
    </div>
  );
}

export function SuccessBanner({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="rounded-md border border-emerald-900/60 bg-emerald-950/40 px-3 py-2 text-sm text-emerald-200"
    >
      {children}
    </div>
  );
}

export function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string;
  htmlFor: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-xs font-medium text-slate-400">
        {label}
      </label>
      {children}
      {error ? (
        <p className="text-xs text-rose-300" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export const inputClasses =
  "w-full rounded-md border border-slate-700 bg-slate-950/60 px-3 py-2 text-sm text-slate-100 " +
  "placeholder:text-slate-600 focus:border-sky-600 focus:outline-none focus:ring-1 focus:ring-sky-600 " +
  "disabled:cursor-not-allowed disabled:opacity-60";

export const primaryButtonClasses =
  "inline-flex w-full items-center justify-center gap-2 rounded-md bg-sky-600 px-4 py-2 text-sm " +
  "font-semibold text-white transition-colors hover:bg-sky-500 focus:outline-none focus:ring-2 " +
  "focus:ring-sky-500 focus:ring-offset-2 focus:ring-offset-slate-950 disabled:cursor-not-allowed " +
  "disabled:opacity-60";

export function StatusPill({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-semibold ring-1 ring-inset ${className}`}
    >
      {children}
    </span>
  );
}
