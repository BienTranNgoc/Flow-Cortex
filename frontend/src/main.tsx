import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initTheme } from './app/useTheme';

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found in index.html');
}

initTheme();

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
