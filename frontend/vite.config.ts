import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// NOTE: add tailwindcss() to plugins below once the team confirms the
// styling approach (Devansh — UI/UX). Don't add unused plugins preemptively.

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), 'VITE_');

    // index.html uses %VITE_APP_NAME% in the title and social tags. Vite
    // only substitutes it when an .env file defines it — this keeps it
    // from leaking literally into the HTML if someone hasn't copied
    // .env.example yet. Mirrors the `?? 'CivicPulse'` fallback in
    // src/config/constants.ts.
    const appName = env.VITE_APP_NAME || 'CivicPulse';

    return {
        plugins: [
            react(),
            {
                name: 'civicpulse:html-app-name',
                transformIndexHtml: {
                    order: 'pre',
                    handler: (html) => html.replaceAll('%VITE_APP_NAME%', appName),
                },
            },
        ],

        build: {
            sourcemap: false,
            target: 'es2020',
            chunkSizeWarningLimit: 700,
        },

        resolve: {
            alias: {
                '@': path.resolve(import.meta.dirname, './src'),
            },
        },

        server: {
            port: 5173,
            strictPort: true,
            proxy: {
                // The /api/core and /api/ai prefixes are a frontend-only routing
                // convention — strip them before forwarding so each backend sees
                // its own prefix-free routes (/health, not /api/ai/health).
                '/api/core': {
                    target: env.VITE_SPRING_API_URL ?? 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                    rewrite: (path) => path.replace(/^\/api\/core/, ''),
                },
                '/api/ai': {
                    target: env.VITE_NODE_API_URL ?? 'http://localhost:3001',
                    changeOrigin: true,
                    secure: false,
                    rewrite: (path) => path.replace(/^\/api\/ai/, ''),
                },
            },
        },
    };
});