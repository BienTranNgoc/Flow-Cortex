import type { WorkflowResponse } from '@flow-cortex/api-client';

interface OwnerCellProps {
  readonly workflow: WorkflowResponse;
  readonly currentUserEmail?: string | undefined;
}

function ownerEmailOf(workflow: WorkflowResponse): string | undefined {
  const candidate = (workflow as unknown as { ownerEmail?: unknown }).ownerEmail;
  return typeof candidate === 'string' ? candidate : undefined;
}

function ownerNameOf(workflow: WorkflowResponse): string | undefined {
  const candidate = (workflow as unknown as { ownerName?: unknown }).ownerName;
  return typeof candidate === 'string' ? candidate : undefined;
}

function initialsFor(name: string | undefined, email: string | undefined): string {
  const source = (name ?? '').trim() || email || '';
  if (!source) return '?';
  const cleaned = source.replace(/[^A-Za-z0-9]+/g, ' ').trim();
  const parts = cleaned.split(/\s+/).filter(Boolean);
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

function isMine(
  workflow: WorkflowResponse,
  currentUserEmail: string | undefined,
): boolean {
  if (!workflow.isShared) return true;
  const ownerEmail = ownerEmailOf(workflow);
  if (!currentUserEmail || !ownerEmail) return false;
  return ownerEmail.toLowerCase() === currentUserEmail.toLowerCase();
}

/**
 * Avatar + tên người tạo. Workflow không có `ownerEmail` thì hiển thị "Shared"
 * (workflow của team) — phân biệt với Mine (workflow cá nhân, isShared=false).
 */
export function OwnerCell({ workflow, currentUserEmail }: Readonly<OwnerCellProps>) {
  const ownerEmail = ownerEmailOf(workflow);
  const ownerName = ownerNameOf(workflow);
  const mine = isMine(workflow, currentUserEmail);
  const displayName = mine
    ? 'You'
    : (ownerName ?? ownerEmail ?? 'Shared team');
  const seed = ownerEmail ?? workflow.id ?? workflow.name ?? 'wf';
  const [from, to] = paletteFor(seed);
  const initials = initialsFor(displayName, ownerEmail);

  return (
    <div className="wf-list__owner">
      <span
        className="wf-list__owner-avatar"
        style={{ background: `linear-gradient(135deg, ${from}, ${to})` }}
        aria-hidden="true"
      >
        {initials}
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
