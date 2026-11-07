import type { ReactNode } from 'react';

interface PageHeadingProps {
  readonly eyebrow?: string;
  readonly title: string;
  readonly description?: string | undefined;
  readonly actions?: ReactNode;
}

/** Shared page header for every admin screen: optional eyebrow, h1, lede and wrapping actions. */
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
