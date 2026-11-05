import { defineConfig } from "vite";
import solid from "vite-plugin-solid";

const backend = process.env["VITE_API_TARGET"] ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [solid()],
  server: {
    host: "127.0.0.1",
    strictPort: true,
    fs: { allow: [import.meta.dirname, `${import.meta.dirname}/../scripts/indesign`] },
    proxy: {
      "/api": backend,
      "/health": backend,
      "/thumbnails": backend,
    },
  },
  build: {
    outDir: "dist",
    target: "es2023",
  },
});
