import { Component, type ReactNode } from 'react';
import { reportClientError } from './reportClientError';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Defaults to reloading the page. */
  onReload?: () => void;
}

interface ErrorBoundaryState {
  failed: boolean;
}

/**
 * Contains a render failure of its children: shows a recoverable error view with "Reload" in their
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
    const reload = this.props.onReload ?? (() => window.location.reload());
    return (
      <section className="error-view">
        <h1>Something went wrong</h1>
        <p role="alert">This page could not be shown. Reload it to try again.</p>
        <button type="button" onClick={reload}>
          Reload
        </button>
      </section>
    );
  }
}
