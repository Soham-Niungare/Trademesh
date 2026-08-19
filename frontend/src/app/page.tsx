"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

import { Spinner } from "@/components/ui";
import { useAuth } from "@/context/AuthContext";

/**
 * Landing route. The token lives in localStorage, which is only readable on the
 * client, so this decides where to go once the auth context has rehydrated
 * rather than being a server-side redirect.
 */
export default function HomePage() {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "loading") return;
    router.replace(status === "authenticated" ? "/dashboard" : "/login");
  }, [status, router]);

  return (
    <main className="flex min-h-screen items-center justify-center">
      <Spinner className="size-6" />
    </main>
  );
}
