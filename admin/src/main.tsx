import { configureClientErrorReporting } from '@flow-cortex/ui-kit';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource-variable/google-sans-flex';
import './lib/i18n';
import { App } from './App';
import { configureAdminClient } from './app/configureClient';
import { initTheme } from './app/useTheme';

configureAdminClient();
configureClientErrorReporting({ app: 'admin' });
initTheme();

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found in index.html');
}

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
