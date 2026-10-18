import type { WorkflowResponse } from '@flow-cortex/api-client';

interface OwnerCellProps {
  readonly workflow: WorkflowResponse;
}

function initialsFor(name: string): string {
  const parts = name.replace(/[^A-Za-z0-9]+/g, ' ').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

function paletteFor(seed: string): readonly [string, string] {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + (seed.codePointAt(i) ?? 0)) >>> 0;
  }
  const hue = hash % 360;
  return [`hsl(${hue} 70% 55%)`, `hsl(${(hue + 40) % 360} 70% 45%)`];
}

/** The API reports only whether a workflow is shared, not who owns it: a personal workflow is "You", a shared one "Shared team". */
export function OwnerCell({ workflow }: Readonly<OwnerCellProps>) {
  const mine = !workflow.isShared;
  const displayName = mine ? 'You' : 'Shared team';
  const [from, to] = paletteFor(workflow.id ?? workflow.name ?? 'wf');

  return (
    <div className="wf-list__owner">
      <span
        className="wf-list__owner-avatar"
        style={{ background: `linear-gradient(135deg, ${from}, ${to})` }}
        aria-hidden="true"
      >
        {initialsFor(displayName)}
      </span>
      <span className="wf-list__owner-name">
        {displayName}
        <span
          className={`wf-list__owner-tag${mine ? ' wf-list__owner-tag--mine' : ''}`}
          aria-label={mine ? 'Owned by you' : 'Shared workflow'}
        >
          {mine ? 'Mine' : 'Shared'}
        </span>
      </span>
    </div>
  );
}
