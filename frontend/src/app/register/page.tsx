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

interface FieldErrors {
  username?: string;
  email?: string;
  password?: string;
}

/** Matches RegisterRequest's constraints: @NotBlank, @Email, @Size(min=8,max=100). */
const MIN_PASSWORD_LENGTH = 8;
const MAX_PASSWORD_LENGTH = 100;

export default function RegisterPage() {
  const { status, register } = useAuth();
  const router = useRouter();

  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") router.replace("/dashboard");
  }, [status, router]);

  function validate(): FieldErrors {
    const errors: FieldErrors = {};
    if (!username.trim()) errors.username = "Username is required.";
    if (!email.trim()) {
      errors.email = "Email is required.";
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      errors.email = "Enter a valid email address.";
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
      errors.password = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
    } else if (password.length > MAX_PASSWORD_LENGTH) {
      errors.password = `Password must be at most ${MAX_PASSWORD_LENGTH} characters.`;
    }
    return errors;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    const errors = validate();
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setSubmitting(true);
    try {
      // Registration returns only a userId, so this signs in straight afterwards
      // and lands on the dashboard rather than bouncing back to a login form.
      await register(username.trim(), email.trim(), password);
      router.replace("/dashboard");
    } catch (caught) {
      // 409 means the username or email is taken -- the backend's message says
      // which, so it is shown as-is.
      setError(
        caught instanceof ApiError
          ? caught.message
          : "Could not create that account. Please try again.",
      );
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      title="Create an account"
      subtitle="Live order book and order entry"
      footer={
        <>
          Already registered?{" "}
          <Link href="/login" className="text-sky-400 hover:text-sky-300">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
        <Field label="Username" htmlFor="username" error={fieldErrors.username}>
          <input
            id="username"
            name="username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className={inputClasses}
          />
        </Field>

        <Field label="Email" htmlFor="email" error={fieldErrors.email}>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className={inputClasses}
          />
        </Field>

        <Field label="Password" htmlFor="password" error={fieldErrors.password}>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={inputClasses}
          />
          <p className="text-xs text-slate-600">At least {MIN_PASSWORD_LENGTH} characters.</p>
        </Field>

        {error ? <ErrorBanner message={error} /> : null}

        <button type="submit" disabled={submitting} className={primaryButtonClasses}>
          {submitting ? <Spinner /> : null}
          {submitting ? "Creating account…" : "Create account"}
        </button>
      </form>
    </AuthShell>
  );
}
