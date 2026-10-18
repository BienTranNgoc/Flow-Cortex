import { setLocaleProvider } from '@flow-cortex/api-client';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { activeLocale } from './lib/i18n';
import { App } from './App';

setLocaleProvider(activeLocale);

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found in index.html');
}

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
