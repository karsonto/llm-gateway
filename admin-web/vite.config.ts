import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: "/admin/",
  build: {
    outDir: "../src/main/resources/static/admin",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/admin/api": {
        target: "http://127.0.0.1:8088",
        changeOrigin: true,
      },
    },
  },
});
