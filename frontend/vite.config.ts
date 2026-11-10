import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Play runs on :9000; the SPA talks to it through this proxy so everything is same-origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9000', changeOrigin: true },
    },
  },
})
