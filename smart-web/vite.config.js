import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { runtimePlugin } from '../scripts/runtime-control.mjs'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), runtimePlugin(fileURLToPath(new URL('../', import.meta.url)))],
  // An open desktop window can still request lazy chunks from its previous build.
  // Keep content-hashed assets so rebuilding does not break those active windows.
  build: { emptyOutDir: false },
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080', // ✅ 改为 Gateway（8080）
        changeOrigin: true
      }
    }
  }
})
