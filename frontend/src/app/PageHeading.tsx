import { forwardRef, type ReactNode } from 'react';
import './shellParts.css';

interface PageHeadingProps {
  readonly title: string;
  readonly lede?: ReactNode;
  readonly actions?: ReactNode;
}

/** The first block of a signed-in page; its `h1` is where focus lands after a page change. */
export const PageHeading = forwardRef<HTMLHeadingElement, PageHeadingProps>(function PageHeading(
  { title, lede, actions },
  ref,
) {
  return (
    <div className="page-heading">
      <div className="page-heading__text">
        <h1 ref={ref} className="page-heading__title" tabIndex={-1}>
          {title}
        </h1>
        {lede && <p className="page-heading__lede">{lede}</p>}
      </div>
      {actions && <div className="page-heading__actions">{actions}</div>}
    </div>
  );
});
