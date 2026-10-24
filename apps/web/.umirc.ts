import { defineConfig } from "umi";
export default defineConfig({
  title: "栖学 · 校园自习室",
  routes: [{ path: "/*", component: "@/pages/App" }],
  npmClient: "pnpm",
  targets: { chrome: 100, firefox: 100, safari: 15, edge: 100 },
  hash: true,
  // Keep a single React instance in development; eager MFSU duplicates React with this locked stack.
  mfsu: false,
  metas: [{ name: "viewport", content: "width=device-width, initial-scale=1" }],
  favicons: ["/favicon.svg"],
  proxy: {
    "/api/v1/repairs": {
      target: process.env.REPAIR_PROXY_URL || "http://127.0.0.1:8082",
      changeOrigin: true,
    },
    "/api": {
      target: process.env.CORE_PROXY_URL || "http://127.0.0.1:8081",
      changeOrigin: true,
    },
  },
});
