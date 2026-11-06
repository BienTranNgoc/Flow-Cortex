import './SessionStatus.css';

/** Flat, centred status shown while the session is checked; it renders before the shell exists. */
export function SessionStatus({ children }: Readonly<{ children: string }>) {
  return (
    <div role="status" className="session-status">
      {children}
    </div>
  );
}
