import './shellParts.css';

interface SkeletonProps {
  readonly shape: 'card' | 'row';
  readonly count?: number;
}

/**
 * Placeholders shaped like the content they stand in for. They are hidden from assistive
 * technology: the region that holds them carries `aria-busy`.
 */
export function Skeleton({ shape, count = 1 }: Readonly<SkeletonProps>) {
  return (
    <>
      {Array.from({ length: count }, (_, index) =>
        shape === 'card' ? (
          <div key={index} className="skeleton-card" aria-hidden="true">
            <div className="skeleton skeleton--media" />
            <div className="skeleton skeleton--title" />
            <div className="skeleton skeleton--line" />
          </div>
        ) : (
          <div key={index} className="skeleton-row" aria-hidden="true">
            <div className="skeleton skeleton--title" />
            <div className="skeleton skeleton--line" />
          </div>
        ),
      )}
    </>
  );
}
