"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { AuthShell } from "@/components/AuthShell";
import {
  ErrorBanner,
  Field,
  Spinner,
  inputClasses,
  primaryButtonClasses,
} from "@/components/ui";
import { useAuth } from "@/context/AuthContext";
import { ApiError } from "@/lib/api";

export default function LoginPage() {
  const { status, logoutReason, login, clearLogoutReason } = useAuth();
  const router = useRouter();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Someone who is already signed in has no reason to see this page.
  useEffect(() => {
    if (status === "authenticated") router.replace("/dashboard");
  }, [status, router]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    if (!username.trim() || !password) {
      setError("Enter both a username and a password.");
      return;
    }

    setSubmitting(true);
    try {
      clearLogoutReason();
      await login(username.trim(), password);
      router.replace("/dashboard");
    } catch (caught) {
      // The backend returns an identical 401 for "no such user" and "wrong
      // password" so the response can't be used to enumerate usernames -- its
      // message is passed through verbatim rather than being narrowed here.
      setError(
        caught instanceof ApiError ? caught.message : "Could not sign in. Please try again.",
      );
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      title="Sign in"
      subtitle="Live order book and order entry"
      footer={
        <>
          No account?{" "}
          <Link href="/register" className="text-sky-400 hover:text-sky-300">
            Create one
          </Link>
        </>
      }
    >
      {logoutReason === "expired" ? (
        <div
          role="status"
          className="mb-4 rounded-md border border-amber-900/60 bg-amber-950/40 px-3 py-2 text-sm text-amber-200"
        >
          Your session expired. Please sign in again.
        </div>
      ) : null}

      <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
        <Field label="Username" htmlFor="username">
          <input
            id="username"
            name="username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className={inputClasses}
          />
        </Field>

        <Field label="Password" htmlFor="password">
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={inputClasses}
          />
        </Field>

        {error ? <ErrorBanner message={error} /> : null}

        <button type="submit" disabled={submitting} className={primaryButtonClasses}>
          {submitting ? <Spinner /> : null}
          {submitting ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </AuthShell>
  );
}
