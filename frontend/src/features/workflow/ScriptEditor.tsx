import { useEffect, useState } from 'react';
import type { ReviewTaskResponse, ScriptRevisionResponse } from '@flow-cortex/api-client';
import { isLostRace } from './lostRace';
import { useSaveRevisionMutation } from './useWorkflows';

interface ScriptEditorProps {
  runId: string;
  task: ReviewTaskResponse;
  revisions: ScriptRevisionResponse[];
  onApprovalBlockedChange: (blocked: boolean) => void;
}

/**
 * The script checkpoint's editor. An edit is not a draft: "Save revision" appends an immutable
 * revision and moves the task's current pointer, so what the reviewer approves afterwards is
 * named by id. That is what lets two tabs disagree safely — the tab holding an older
 * `currentRevisionId` loses the approval with a 409 instead of approving text it never saw.
 */
export function ScriptEditor({
  runId,
  task,
  revisions,
  onApprovalBlockedChange,
}: Readonly<ScriptEditorProps>) {
  const latest = revisions.at(-1);
  const saved = latest?.content ?? '';
  const [draft, setDraft] = useState(saved);
  const save = useSaveRevisionMutation(runId, task.id ?? '');
  const dirty = draft.trim() !== saved.trim();
  const revisionIsCurrent = latest?.id === task.currentRevisionId && task.version !== undefined;

  // Another tab can move the current revision while this one is open; adopting its text is what
  // makes the 409 below the answer, rather than this editor silently holding text nobody can approve.
  useEffect(() => {
    if (!dirty) {
      setDraft(saved);
    }
  }, [saved, dirty]);

  useEffect(() => {
    onApprovalBlockedChange(dirty || save.isPending || !revisionIsCurrent);
  }, [dirty, onApprovalBlockedChange, revisionIsCurrent, save.isPending]);

  useWarnOnUnsavedChanges(dirty);

  return (
    <div className="script-editor">
      <RevisionHistory revisions={revisions} approvedRevisionId={task.approvedRevisionId} />
      <div className="field">
        <label htmlFor="script-draft">Script to approve</label>
        <textarea
          id="script-draft"
          rows={6}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        />
      </div>
      <button
        type="button"
        onClick={() => {
          if (!dirty || save.isPending || task.version === undefined) return;
          save.mutate({ content: draft, expectedVersion: task.version });
        }}
        disabled={!dirty || save.isPending || task.version === undefined}
        aria-busy={save.isPending}
      >
        {save.isPending ? 'Saving…' : 'Save revision'}
      </button>
      {dirty && <p className="field-hint">Unsaved changes — save a revision before approving.</p>}
      {save.isError && (
        <p role="alert" className="approval-error">
          {isLostRace(save.error)
            ? 'This script moved on in another tab. Reload the page to see the latest revision.'
            : 'Could not save this revision'}
        </p>
      )}
    </div>
  );
}

/**
 * A revision is only durable once saved, so an unsaved edit has to survive a reload — a tab
 * closed by accident would otherwise lose the text with no trace and no way to recover it.
 */
function useWarnOnUnsavedChanges(dirty: boolean) {
  useEffect(() => {
    if (!dirty) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    const warnForInternalLink = (event: MouseEvent) => {
      if (
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey ||
        !(event.target instanceof Element)
      ) {
        return;
      }
      const anchor = event.target.closest<HTMLAnchorElement>('a[href]');
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) {
        return;
      }
      const destination = new URL(anchor.href, window.location.href);
      if (destination.origin !== window.location.origin) {
        return;
      }
      if (!window.confirm('You have unsaved script changes. Leave this page?')) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    window.addEventListener('beforeunload', warn);
    document.addEventListener('click', warnForInternalLink, true);
    return () => {
      window.removeEventListener('beforeunload', warn);
      document.removeEventListener('click', warnForInternalLink, true);
    };
  }, [dirty]);
}

function RevisionHistory({
  revisions,
  approvedRevisionId,
}: Readonly<{
  revisions: ScriptRevisionResponse[];
  approvedRevisionId: string | undefined;
}>) {
  if (revisions.length === 0) {
    return null;
  }
  return (
    <ol className="revision-history" aria-label="Script revisions">
      {revisions.map((revision) => (
        <li key={revision.id}>
          <span className="revision-number">Revision {revision.revisionNumber}</span>
          {revision.id === approvedRevisionId && (
            <span className="revision-approved">Approved</span>
          )}
          <span className="revision-preview">{revision.content}</span>
        </li>
      ))}
    </ol>
  );
}
