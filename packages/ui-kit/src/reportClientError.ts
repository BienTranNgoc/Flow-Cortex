export type ClientErrorKind = 'render' | 'query';
export type ClientErrorApp = 'member' | 'admin';

const ENDPOINT = '/api/v1/client-errors';
const MAX_REPORTS_PER_PAGE_LOAD = 10;
const MAX_NAME = 80;
const MAX_ROUTE = 120;
const MAX_LANG = 8;

let app: ClientErrorApp | null = null;
let sent = 0;
const seen = new Set<string>();

/** Enables posting to the intake for this page load; until called, errors only reach the console. */
export function configureClientErrorReporting(options: { app: ClientErrorApp }): void {
  app = options.app;
}

/** Clears configuration and the page-load budget so each test starts as a fresh page. */
export function resetClientErrorReportingForTests(): void {
  app = null;
  sent = 0;
  seen.clear();
}

/**
 * Logs a caught client error (route path only, error name, HTTP status when present) and, once
 * configured, posts it to the intake. Never sends the message: an API error's message is the server's
 * Problem `detail`, which can name a person. A status-carrying error is not posted: the backend saw it.
 */
export function reportClientError(error: unknown, kind: ClientErrorKind = 'query'): void {
  const name = error instanceof Error && error.name ? error.name : 'NonError';
  const status = (error as { status?: unknown } | null)?.status;
  const route = window.location.pathname;
  console.error('client_error', {
    route,
    name,
    ...(typeof status === 'number' ? { status } : {}),
  });
  if (app === null || typeof status === 'number') {
    return;
  }
  post(app, kind, name.slice(0, MAX_NAME), route.slice(0, MAX_ROUTE));
}

function post(reportingApp: ClientErrorApp, kind: ClientErrorKind, name: string, route: string): void {
  const key = `${kind}|${name}|${route}`;
  if (sent >= MAX_REPORTS_PER_PAGE_LOAD || seen.has(key)) {
    return;
  }
  seen.add(key);
  sent += 1;
  const lang = document.documentElement.lang.slice(0, MAX_LANG);
  const body = JSON.stringify({
    v: 1,
    kind,
    app: reportingApp,
    name,
    route,
    ...(lang ? { lang } : {}),
  });
  void deliver(body);
}

async function deliver(body: string): Promise<void> {
  try {
    await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body,
      credentials: 'omit',
      keepalive: true,
      referrerPolicy: 'no-referrer',
    });
  } catch {
    // Best-effort delivery: a failed post must never become a new page error.
  }
}
