import type { ReactNode } from 'react';

interface EmptyStateProps {
  readonly title: string;
  readonly description?: string;
  readonly action?: ReactNode;
}

/** Centered panel for "nothing here yet" — keeps the same surface language
 *  as the rest of the admin so the page doesn't feel half-rendered. */
export function EmptyState({ title, description, action }: Readonly<EmptyStateProps>) {
  return (
    <div className="cortex-admin-empty" role="status">
      <p>
        <strong>{title}</strong>
      </p>
      {description && <p>{description}</p>}
      {action && <div>{action}</div>}
    </div>
  );
}
