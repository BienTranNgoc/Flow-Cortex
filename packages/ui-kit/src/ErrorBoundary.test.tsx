import { fireEvent, render, screen } from '@testing-library/react';
import type { MockInstance } from 'vitest';
import { ErrorBoundary } from './ErrorBoundary';
import {
  configureClientErrorReporting,
  reportClientError,
  resetClientErrorReportingForTests,
} from './reportClientError';

function Explodes(): never {
  throw new TypeError('Cannot read properties of undefined (reading "durationMs")');
}

const LABELS = {
  title: 'Something went wrong',
  message: 'This page could not be shown. Reload it to try again.',
  reload: 'Reload',
};

const RETRY_LABELS = { ...LABELS, retry: 'Try again' };

const flaky = { broken: false };

function Flaky() {
  if (flaky.broken) {
    throw new TypeError('flaky');
  }
  return <p>Run page</p>;
}

type ConsoleSpy = MockInstance<Parameters<Console['error']>, void>;

function clientEvents(spy: ConsoleSpy) {
  return spy.mock.calls.filter(([first]) => first === 'client_error');
}

describe('ErrorBoundary', () => {
  let consoleError: ConsoleSpy;

  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    window.history.pushState({}, '', '/workflows/w1/runs/r1?token=secret');
  });
  afterEach(() => {
    consoleError.mockRestore();
  });

  it('contains a render failure in an error view with a Reload action when it cannot retry', () => {
    render(
      <div>
        <nav>Workflows</nav>
        <ErrorBoundary labels={LABELS}>
          <Explodes />
        </ErrorBoundary>
      </div>,
    );

    expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.getByText('Workflows')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/^This page could not be shown\. Reload it to try again\.$/);
  });

  it('offers Try again on the first failure and renders the children again', () => {
    flaky.broken = true;
    render(
      <ErrorBoundary labels={RETRY_LABELS}>
        <Flaky />
      </ErrorBoundary>,
    );

    expect(screen.queryByRole('button', { name: 'Reload' })).not.toBeInTheDocument();
    flaky.broken = false;
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.getByText('Run page')).toBeInTheDocument();
  });

  it('offers Reload when the retry fails too, and Reload calls onReload', () => {
    const reload = vi.fn();
    flaky.broken = true;
    render(
      <ErrorBoundary labels={RETRY_LABELS} onReload={reload}>
        <Flaky />
      </ErrorBoundary>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('offers Reload only on the whole-app view and marks it', () => {
    const { container } = render(
      <ErrorBoundary variant="root" labels={RETRY_LABELS}>
        <Explodes />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(container.querySelector('.error-view--root')).not.toBeNull();
  });

  it('renders the silent fallback and reports once', () => {
    render(
      <div>
        <ErrorBoundary fallback={<p>Quiet</p>}>
          <Explodes />
        </ErrorBoundary>
      </div>,
    );

    expect(screen.getByText('Quiet')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(clientEvents(consoleError)).toHaveLength(1);
  });

  it('reports the failure once as a structured event with the route and the error name', () => {
    render(
      <ErrorBoundary labels={LABELS}>
        <Explodes />
      </ErrorBoundary>,
    );

    expect(clientEvents(consoleError)).toEqual([
      [
        'client_error',
        { route: '/workflows/w1/runs/r1', name: 'TypeError' },
      ],
    ]);
  });
});

describe('reportClientError', () => {
  let consoleError: ConsoleSpy;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    resetClientErrorReportingForTests();
    document.documentElement.lang = 'en';
  });
  afterEach(() => {
    consoleError.mockRestore();
    vi.unstubAllGlobals();
    document.documentElement.lang = '';
  });

  it('reports a thrown value that is not an Error without its content', () => {
    configureClientErrorReporting({ app: 'member' });
    window.history.pushState({}, '', '/profile');

    reportClientError({ password: 'hunter2' });

    expect(consoleError).toHaveBeenCalledWith('client_error', { route: '/profile', name: 'NonError' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const body = String(fetchMock.mock.calls[0]?.[1]?.body);
    expect(JSON.parse(body)).toMatchObject({ name: 'NonError', route: '/profile' });
    expect(body).not.toContain('hunter2');
  });

  it('reports an API error by its status and never its message, which can carry personal data', () => {
    class ApiError extends Error {
      readonly status = 404;
      override readonly name = 'ApiError';
    }
    configureClientErrorReporting({ app: 'member' });
    window.history.pushState({}, '', '/users');

    reportClientError(new ApiError('User not found: member1@flowcortex.dev'));

    expect(consoleError.mock.calls).toEqual([
      ['client_error', { route: '/users', name: 'ApiError', status: 404 }],
    ]);
    expect(JSON.stringify(consoleError.mock.calls)).not.toContain('@');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('posts a render failure to the intake without its message, cookies or referrer', () => {
    configureClientErrorReporting({ app: 'member' });
    window.history.pushState({}, '', '/workflows/42?x=1');

    reportClientError(new TypeError('cannot read user an@example.com'), 'render');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/client-errors');
    expect(init.method).toBe('POST');
    expect(init.headers).toEqual({ 'Content-Type': 'text/plain;charset=UTF-8' });
    expect(init.credentials).toBe('omit');
    expect(init.keepalive).toBe(true);
    expect(init.referrerPolicy).toBe('no-referrer');
    expect(init.body).toBe(
      '{"v":1,"kind":"render","app":"member","name":"TypeError","route":"/workflows/42","lang":"en"}',
    );
    expect(String(init.body)).not.toContain('an@example.com');
  });

  it('omits the language when the page declares none and caps name and route', () => {
    configureClientErrorReporting({ app: 'admin' });
    document.documentElement.lang = '';
    window.history.pushState({}, '', `/${'a'.repeat(200)}`);
    class Long extends Error {
      override readonly name = 'N'.repeat(100);
    }

    reportClientError(new Long('x'));

    const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body)) as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(['v', 'kind', 'app', 'name', 'route']);
    expect(body.app).toBe('admin');
    expect(String(body.name)).toHaveLength(80);
    expect(String(body.route)).toHaveLength(120);
  });

  it('reports a query failure without a status as kind query', () => {
    configureClientErrorReporting({ app: 'member' });

    reportClientError(new TypeError('Failed to fetch'));

    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toMatchObject({ kind: 'query' });
  });

  it('sends nothing before the app is configured', () => {
    reportClientError(new TypeError('boom'), 'render');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(consoleError).toHaveBeenCalledWith('client_error', expect.objectContaining({ name: 'TypeError' }));
  });

  it('sends one report for a failure that repeats on one route', () => {
    configureClientErrorReporting({ app: 'member' });
    window.history.pushState({}, '', '/loop');

    for (let i = 0; i < 50; i += 1) {
      reportClientError(new TypeError('again'), 'render');
    }

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends at most ten reports in one page load', () => {
    configureClientErrorReporting({ app: 'member' });
    window.history.pushState({}, '', '/many');

    for (let i = 0; i < 15; i += 1) {
      const error = new Error('x');
      error.name = `Failure${i}`;
      reportClientError(error);
    }

    expect(fetchMock).toHaveBeenCalledTimes(10);
  });

  it('never throws and never retries when delivery fails', async () => {
    configureClientErrorReporting({ app: 'member' });
    window.history.pushState({}, '', '/down');
    const first = new TypeError('a');
    const second = new RangeError('b');

    fetchMock.mockImplementationOnce(() => {
      throw new TypeError('sync failure');
    });
    expect(() => reportClientError(first)).not.toThrow();
    fetchMock.mockRejectedValueOnce(new TypeError('network down'));
    expect(() => reportClientError(second)).not.toThrow();
    await Promise.resolve();

    reportClientError(first);
    reportClientError(second);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
