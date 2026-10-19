import { Component, type ReactNode } from 'react';
import { reportClientError } from './reportClientError';

export interface ErrorBoundaryLabels {
  title: string;
  message: string;
  reload: string;
}

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Required so a missing translation is a type error, never an English fallback. */
  labels: ErrorBoundaryLabels;
  /** Defaults to reloading the page. */
  onReload?: () => void;
}

interface ErrorBoundaryState {
  failed: boolean;
}

/**
 * Contains a render failure of its children: shows a recoverable error view with a reload action in their
 * place, so the surrounding shell keeps working, and reports the failure once.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  override componentDidCatch(error: unknown): void {
    reportClientError(error);
  }

  override render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    const { labels } = this.props;
    const reload = this.props.onReload ?? (() => window.location.reload());
    return (
      <section className="error-view">
        <h1>{labels.title}</h1>
        <p role="alert">{labels.message}</p>
        <button type="button" onClick={reload}>
          {labels.reload}
        </button>
      </section>
    );
  }
}
