import type { ReactNode } from "react";

/** Centred card layout shared by the sign-in and registration pages. */
export function AuthShell({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
  footer: ReactNode;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-8 text-center">
          <h1 className="text-2xl font-bold tracking-tight text-slate-100">TradeMesh</h1>
          <p className="mt-1 text-sm text-slate-500">{subtitle}</p>
        </div>

        <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-6">
          <h2 className="mb-5 text-sm font-semibold text-slate-200">{title}</h2>
          {children}
        </div>

        <p className="mt-6 text-center text-sm text-slate-500">{footer}</p>
      </div>
    </main>
  );
}
