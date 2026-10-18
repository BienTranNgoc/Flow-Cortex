import type { ReactNode } from 'react';

interface PageHeadingProps {
  readonly eyebrow?: string;
  readonly title: string;
  readonly description?: string | undefined;
  readonly actions?: ReactNode;
}

/**
 * Shared page header for every admin screen. Mirrors the structure of the
 * `HomePage` hero block from the member SPA (eyebrow + h1 + lede + actions)
 * so the two consoles feel like the same product.
 */
export function PageHeading({ eyebrow, title, description, actions }: Readonly<PageHeadingProps>) {
  return (
    <header className="cortex-admin-page-heading">
      <div>
        {eyebrow && <p className="cortex-admin-eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="cortex-admin-page-description">{description}</p>}
      </div>
      {actions && <div className="cortex-admin-page-heading__actions">{actions}</div>}
    </header>
  );
}
