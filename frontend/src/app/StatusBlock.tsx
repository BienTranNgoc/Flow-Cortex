import { Link } from 'react-router-dom';
import './shellParts.css';

interface StatusAction {
  readonly label: string;
  readonly onClick?: () => void;
  /** A destination instead of a handler. */
  readonly to?: string;
}

interface StatusBlockProps {
  readonly kind: 'error' | 'empty';
  readonly message: string;
  readonly action: StatusAction;
}

/** One line and one action: an inline error with its retry, or an empty or no-match result with its way out. */
export function StatusBlock({ kind, message, action }: Readonly<StatusBlockProps>) {
  return (
    <div
      className={kind === 'error' ? 'status-block status-block--error' : 'status-block'}
      role={kind === 'error' ? 'alert' : undefined}
    >
      <p className="status-block__message">{message}</p>
      {action.to ? (
        <Link to={action.to} className="action-link">
          {action.label}
        </Link>
      ) : (
        <button type="button" onClick={action.onClick}>
          {action.label}
        </button>
      )}
    </div>
  );
}
