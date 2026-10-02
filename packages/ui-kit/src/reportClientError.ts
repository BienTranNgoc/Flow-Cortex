/**
 * Reports a caught client error as one structured console event: the route (path only, never the
 * query string), the error's name and, for an HTTP error, its status. Never the message: an API
 * error's message is the server's Problem `detail`, which can name a person.
 */
export function reportClientError(error: unknown): void {
  const name = error instanceof Error ? error.name : 'NonError';
  const status = (error as { status?: unknown } | null)?.status;
  console.error('client_error', {
    route: window.location.pathname,
    name,
    ...(typeof status === 'number' ? { status } : {}),
  });
}
