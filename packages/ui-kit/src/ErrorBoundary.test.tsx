import { render, screen } from '@testing-library/react';
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

  it('renders its children when nothing throws', () => {
    render(
      <ErrorBoundary labels={LABELS}>
        <p>Run page</p>
      </ErrorBoundary>,
    );

    expect(screen.getByText('Run page')).toBeInTheDocument();
  });

  it('contains a render failure in an error view with a Reload action', () => {
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
    expect(screen.getByText('Workflows')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/^This page could not be shown\. Reload it to try again\.$/);
  });

  it('shows the heading, message and reload action it is given', () => {
    render(
      <ErrorBoundary
        labels={{
          title: 'Đã xảy ra lỗi',
          message: 'Không thể hiển thị trang này. Hãy tải lại để thử lần nữa.',
          reload: 'Tải lại',
        }}
      >
        <Explodes />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('heading', { name: 'Đã xảy ra lỗi' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(
      /^Không thể hiển thị trang này\. Hãy tải lại để thử lần nữa\.$/,
    );
    expect(screen.getByRole('button', { name: 'Tải lại' })).toBeInTheDocument();
    expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
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

  it('reloads the page from the Reload action', () => {
    const reload = vi.fn();
    render(
      <ErrorBoundary labels={LABELS} onReload={reload}>
        <Explodes />
      </ErrorBoundary>,
    );

    screen.getByRole('button', { name: 'Reload' }).click();

    expect(reload).toHaveBeenCalledTimes(1);
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
