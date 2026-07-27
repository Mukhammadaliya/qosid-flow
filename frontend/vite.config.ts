/// <reference types="vitest" />
import { defineConfig } from 'vite';
import { configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Same-origin: dev server proxies API calls to the Spring app on :8090.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // Split the two vendor blocks that never change between releases, so a rebuild of the
        // app code does not force operators to re-download React and antd. Everything else
        // splits by route (see the lazy() imports in App.tsx) — notably @ant-design/plots,
        // which lands in the Statistics chunk instead of the entry bundle.
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
        },
      },
    },
  },
  server: {
    proxy: {
      '/qosid': 'http://localhost:8090',
      '/actuator': 'http://localhost:8090',
      // ATAYLAB '/ops/api' — '/ops' SPA'ning o'z sahifa-route'i, proxy qilinmasligi kerak.
      '/ops/api': 'http://localhost:8092',
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
