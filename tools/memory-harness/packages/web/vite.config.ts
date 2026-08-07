import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * Vite config skeleton for @tlm-harness/web.
 * /api is proxied to the harness server (default port 7421).
 */
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://127.0.0.1:7421",
        changeOrigin: true,
      },
    },
  },
});
