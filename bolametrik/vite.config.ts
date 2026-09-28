/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

// Seluruh aplikasi dibundel menjadi satu file HTML (dist/index.html) agar bisa
// dibuka offline, di-hosting statis, dibungkus Capacitor (APK), atau dipublikasikan
// sebagai Artifact claude.ai.
export default defineConfig({
  base: "./",
  plugins: [react(), viteSingleFile()],
  build: {
    target: "es2020",
    chunkSizeWarningLimit: 4000,
  },
  test: {
    include: ["tests/**/*.test.ts"],
  },
});
