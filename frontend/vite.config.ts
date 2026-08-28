import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// NOTE: add tailwindcss() to plugins below once the team confirms the
// styling approach (Devansh — UI/UX). Don't add unused plugins preemptively.

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), 'VITE_');

    return {
        plugins: [react()],

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
                '/api/core': {
                    target: env.VITE_SPRING_API_URL ?? 'http://localhost:8080',
                    changeOrigin: true,
                    secure: false,
                },
                '/api/ai': {
                    target: env.VITE_NODE_API_URL ?? 'http://localhost:3001',
                    changeOrigin: true,
                    secure: false,
                },
            },
        },
    };
});