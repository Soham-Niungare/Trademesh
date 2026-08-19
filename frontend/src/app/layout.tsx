import type { Metadata } from "next";
import type { ReactNode } from "react";
import { Geist, Geist_Mono } from "next/font/google";

import { AuthProvider } from "@/context/AuthContext";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "TradeMesh",
  description: "Live order book, order entry, and trade tape for the TradeMesh matching engine.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // suppressHydrationWarning covers this element's own attributes only -- it
    // does NOT propagate to children, so a genuine mismatch anywhere inside
    // still reports normally. It is here because browser extensions commonly
    // write attributes onto <html> after the server HTML arrives but before
    // React hydrates (e.g. the Scribe recorder's data-scribe-recorder-ready),
    // which React then reports as a mismatch even though nothing this app
    // renders differs between server and client.
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <body className="min-h-full font-sans">
        {/*
          AuthProvider wraps the whole tree so the token survives client-side
          navigation between /login, /register and /dashboard without a refetch,
          and so the global 401 handler is registered exactly once.
        */}
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
