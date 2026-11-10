import { Component, type ReactNode } from 'react';
import { recoveryFor } from './errorRecovery';
import { reportClientError } from './reportClientError';

export interface ErrorBoundaryLabels {
  title: string;
  message: string;
  reload: string;
  /** When present the first failure of a page offers Try again before Reload. */
  retry?: string;
}

type ErrorBoundaryProps = {
  children: ReactNode;
  /** `root` is the whole-app view; `page` is one route's area. */
  variant?: 'root' | 'page';
  /** Defaults to reloading the page. */
  onReload?: () => void;
} & (
  | {
      /** Required so a missing translation is a type error, never an English fallback. */
      labels: ErrorBoundaryLabels;
      fallback?: never;
    }
  | {
      /** Rendered in place of the children after a failure, with no error view. */
      fallback: ReactNode;
      labels?: never;
    }
);

interface ErrorBoundaryState {
  failed: boolean;
  failedRetries: number;
}

/**
 * Contains a render failure of its children: shows a recoverable error view in their place (Try again,
 * then Reload) or a silent fallback, so the surrounding shell keeps working, and reports each failure once.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { failed: false, failedRetries: 0 };

  static getDerivedStateFromError(): Partial<ErrorBoundaryState> {
    return { failed: true };
  }

  override componentDidCatch(error: unknown): void {
    reportClientError(error);
  }

  private readonly retry = () => {
    this.setState((state) => ({ failed: false, failedRetries: state.failedRetries + 1 }));
  };

  override render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    const { labels, fallback, variant = 'page' } = this.props;
    const recovery = recoveryFor({
      variant,
      failedRetries: this.state.failedRetries,
      canRetry: Boolean(labels?.retry),
      silent: labels === undefined,
    });
    if (recovery === 'silent' || labels === undefined) {
      return fallback;
    }
    const reload = this.props.onReload ?? (() => window.location.reload());
    return (
      <section className={variant === 'root' ? 'error-view error-view--root' : 'error-view'}>
        <h1>{labels.title}</h1>
        <p role="alert">{labels.message}</p>
        {recovery === 'retry' ? (
          <button type="button" onClick={this.retry}>
            {labels.retry}
          </button>
        ) : (
          <button type="button" onClick={reload}>
            {labels.reload}
          </button>
        )}
      </section>
    );
  }
}
