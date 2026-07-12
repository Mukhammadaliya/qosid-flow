/// <reference types="vitest" />
import { defineConfig } from 'vite';
import { configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Same-origin: dev server proxies API calls to the Spring app on :8090.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    proxy: {
      '/qosid': 'http://localhost:8090',
      '/actuator': 'http://localhost:8090',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    // Playwright owns e2e/ (its own runner) — keep it out of Vitest.
    exclude: [...configDefaults.exclude, 'e2e/**'],
    setupFiles: ['./src/test-setup.ts'],
    css: false,
  },
});
