import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
    plugins: [vue()],
    // relative base so the build works when served from any path
    base: './',
    build: {
        // the server serves the built frontend from server/public.
        // Docker builds override this via WEB_OUT_DIR to keep the dist inside
        // the builder stage (see server/Dockerfile).
        outDir: process.env.WEB_OUT_DIR || '../server/public',
        emptyOutDir: true
    },
    server: {
        // dev convenience: forward websocket/status calls to a locally
        // running radar server (node server/src/index.js)
        proxy: {
            '/ws': { target: 'ws://127.0.0.1:27081', ws: true },
            '/push': { target: 'ws://127.0.0.1:27081', ws: true },
            '/api': { target: 'http://127.0.0.1:27081' }
        }
    }
})
