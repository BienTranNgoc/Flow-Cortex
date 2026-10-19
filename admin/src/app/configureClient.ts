import { setClientId, setLocaleProvider } from '@flow-cortex/api-client';
import { activeLocale } from '../lib/i18n';

/**
 * Marks this app's auth calls so the backend keeps the admin refresh cookie apart from the member
 * app's, and sends the active UI language with every request.
 */
export function configureAdminClient(): void {
  setClientId('admin');
  setLocaleProvider(activeLocale);
}
