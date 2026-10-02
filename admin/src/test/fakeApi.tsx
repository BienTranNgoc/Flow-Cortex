import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

export interface FakeReply {
  status: number;
  body?: unknown;
}

export interface RecordedCall {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
}

export type Handler = FakeReply | (() => FakeReply | Promise<FakeReply>);

/**
 * Replaces `fetch` with a router keyed `"METHOD /path"`; an unmatched request answers 404.
 * Returns the list every request is appended to.
 */
export function stubApi(routes: Record<string, Handler>): RecordedCall[] {
  const calls: RecordedCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const [path = input, search = ''] = input.split('?');
      calls.push({
        method,
        path,
        query: new URLSearchParams(search),
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      });
      const handler = routes[`${method} ${path}`];
      const reply = typeof handler === 'function' ? await handler() : handler;
      const { status, body } = reply ?? { status: 404, body: { status: 404, detail: 'Not found' } };
      return new Response(body === undefined ? null : JSON.stringify(body), { status });
    }),
  );
  return calls;
}

export function testQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

/** Renders `element` on `path` inside a fresh query client, starting at `url`. */
export function renderRoute(path: string, url: string, element: ReactElement, extra?: ReactElement) {
  const queryClient = testQueryClient();
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path={path} element={element} />
          {extra}
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

/** Waits until no query is in flight, so an absence check cannot pass before the data arrived. */
export async function settled(queryClient: QueryClient) {
  await waitFor(() => expect(queryClient.isFetching()).toBe(0));
}

export const ADMIN_ME = { id: 'a1', email: 'admin@flowcortex.dev', name: 'Admin', role: 'ADMIN' };
export const MEMBER_ME = { id: 'm1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' };
