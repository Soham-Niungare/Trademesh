import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /*
   * Emits .next/standalone -- a self-contained server bundle with only the
   * node_modules actually reachable from the built app, plus a generated
   * server.js. The Docker runtime stage copies that instead of the full
   * node_modules tree, which is the difference between a ~200MB image and a
   * ~1GB one. Nothing outside the Docker build depends on this: `next dev`
   * and `next start` behave exactly as before.
   */
  output: "standalone",
};

export default nextConfig;
