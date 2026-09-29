import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Separate origin, no API proxy, no runtime plugin, and no real accounts.
export default defineConfig({
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 5179, strictPort: true,
    headers: { 'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self' ws://127.0.0.1:5179; frame-src 'self'; object-src 'none'; base-uri 'self'" } },
})
