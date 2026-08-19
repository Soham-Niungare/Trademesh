"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";

import { Spinner } from "@/components/ui";
import { useAuth } from "@/context/AuthContext";

/**
 * Route guard for everything under /dashboard.
 *
 * This is a UX guard, not a security boundary -- it runs in the browser and can
 * be bypassed trivially. It doesn't need to be more than that: every order
 * route is authenticated server-side, and a request without a valid token gets
 * a 401 from Spring Security regardless of what this component renders.
 *
 * Rendering waits for `status` to leave "loading" so a signed-in user reloading
 * the page isn't bounced to /login for the one frame before localStorage has
 * been read.
 */
export default function DashboardLayout({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "anonymous") router.replace("/login");
  }, [status, router]);

  if (status !== "authenticated") {
    return (
      <main className="flex min-h-screen items-center justify-center">
        <Spinner className="size-6" />
      </main>
    );
  }

  return <>{children}</>;
}
