import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { queryClient } from './lib/queryClient';
// Self-hosted and bundled by Vite, so they load from 'self' and satisfy the CSP
// in index.html — which has no font-src directive and therefore falls back to
// default-src 'self'. A Google Fonts <link> would be blocked outright.
import '@fontsource-variable/dm-sans';
import '@fontsource-variable/playfair-display';
// Poppins has no variable build on Google Fonts, so its weights are imported
// individually rather than pulling all nine statics via the package index.
import '@fontsource/poppins/400.css';
import '@fontsource/poppins/500.css';
import '@fontsource/poppins/600.css';
import './index.css';

// The document title comes from index.html (<title>%VITE_APP_NAME% — …</title>,
// substituted at build time). No runtime override here — it would only strip
// the tagline.

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('Root element not found');

createRoot(rootEl).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>
);