import { fireEvent, render, screen } from '@testing-library/react';
import type { MockInstance } from 'vitest';
import { ErrorBoundary } from './ErrorBoundary';
import { reportClientError } from './reportClientError';

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
  it('reports a thrown value that is not an Error without its content', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    window.history.pushState({}, '', '/profile');

    reportClientError({ password: 'hunter2' });

    expect(consoleError).toHaveBeenCalledWith('client_error', { route: '/profile', name: 'NonError' });
    consoleError.mockRestore();
  });

  it('reports an API error by its status and never its message, which can carry personal data', () => {
    class ApiError extends Error {
      readonly status = 404;
      override readonly name = 'ApiError';
    }
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    window.history.pushState({}, '', '/users');

    reportClientError(new ApiError('User not found: member1@flowcortex.dev'));

    expect(consoleError.mock.calls).toEqual([
      ['client_error', { route: '/users', name: 'ApiError', status: 404 }],
    ]);
    expect(JSON.stringify(consoleError.mock.calls)).not.toContain('@');
    consoleError.mockRestore();
  });
});
