import { setClientId } from '@flow-cortex/api-client';

/** Marks this app's auth calls so the backend keeps the admin refresh cookie apart from the member app's. */
export function configureAdminClient(): void {
  setClientId('admin');
}
