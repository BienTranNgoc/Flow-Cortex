import type { ReactNode } from 'react';

interface MetricCardProps {
  readonly label: string;
  readonly value: ReactNode;
  readonly hint?: string;
}

/** One tile in the dashboard's metric grid. Loading state uses the literal
 *  dash; call sites pass the actual number, or a fallback. */
export function MetricCard({ label, value, hint }: Readonly<MetricCardProps>) {
  return (
    <article className="cortex-admin-metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
      {hint && <small>{hint}</small>}
    </article>
  );
}
