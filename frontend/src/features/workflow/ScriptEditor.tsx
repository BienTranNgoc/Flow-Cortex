import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReviewTaskResponse, ScriptRevisionResponse } from '@flow-cortex/api-client';
import { isLostRace } from './lostRace';
import { useSaveRevisionMutation } from './useWorkflows';

/**
 * The script generator aims for 22–30 whitespace-separated words to fill about 10 seconds of
 * speech, and the video is 10 seconds long; past this many words the voice-over likely overruns.
 */
const MAX_SCRIPT_WORDS = 30;
const MAX_MC_SEGMENT_WORDS = 15;

function wordCount(text: string): number {
  return text.split(/\s+/).filter(Boolean).length;
}

function sanitizeMcSegment(text: string): string {
  return text.replaceAll('\r\n', '\n').replace(/\n{2,}/g, '\n').trim();
}

const SENTENCE_END_RE = /[.!?…]/;
const LINE_BREAK_RE = /[\r\n\u2028\u2029]/;

function findSentenceBoundary(text: string): number {
  const firstBr = text.search(LINE_BREAK_RE);
  if (firstBr === -1) {
    const relIdx = text.slice(1).search(/[.!?…]\s/);
    return relIdx === -1 ? -1 : relIdx + 1;
  }
  let lastBr = text.length - 1;
  while (lastBr > firstBr && !LINE_BREAK_RE.test(text[lastBr] ?? '')) {
    lastBr -= 1;
  }
  let idx = firstBr - 1;
  while (idx >= 0 && /\s/.test(text[idx] ?? '')) {
    idx -= 1;
  }
  if (idx < 1 || !SENTENCE_END_RE.test(text[idx] ?? '')) return -1;
  if (/\S/.test(text.slice(idx + 1, lastBr + 1))) return -1;
  return idx;
}

export function splitMcScript(text: string): [string, string] {
  const normalized = text.replaceAll('\r\n', '\n');
  if (!normalized.trim()) return ['', ''];
  const sep = normalized.indexOf('\n\n');
  if (sep !== -1) {
    return [
      sanitizeMcSegment(normalized.slice(0, sep)),
      sanitizeMcSegment(normalized.slice(sep + 2)),
    ];
  }
  const trimmed = normalized.trim();
  const lines = trimmed.split('\n').map((l) => l.trim()).filter(Boolean);
  if (lines.length >= 2) {
    return [lines[0] ?? '', lines.slice(1).join(' ')];
  }
  const boundaryIdx = findSentenceBoundary(trimmed);
  if (boundaryIdx !== -1) {
    const first = trimmed.slice(0, boundaryIdx + 1).trim();
    const rest = trimmed.slice(boundaryIdx + 1).trim();
    if (first && rest) return [first, rest];
  }
  return [trimmed, ''];
}

export function joinMcScript(mcHook: string, productClose: string): string {
  const first = sanitizeMcSegment(mcHook);
  const second = sanitizeMcSegment(productClose);
  if (!first && !second) return '';
  return `${first}\n\n${second}`;
}

function McSegmentField({
  id,
  label,
  value,
  onChange,
}: Readonly<{
  id: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
}>) {
  const { t } = useTranslation();
  const words = wordCount(value);
  const over = words > MAX_MC_SEGMENT_WORDS;
  const pct = Math.min(100, Math.round((words / MAX_MC_SEGMENT_WORDS) * 100));
  return (
    <div className="field">
      <div className="pv-field-header">
        <label htmlFor={id}>{label}</label>
        <span className={over ? 'pv-script-counter pv-script-counter--over' : 'pv-script-counter'}>
          {t('workflow.script.segmentMeter', {
            words,
            max: MAX_MC_SEGMENT_WORDS,
            seconds: Math.max(0, Math.round(words / 3)),
          })}
        </span>
      </div>
      <div className={over ? 'pv-script-meter pv-script-meter--over' : 'pv-script-meter'}>
        <div className="pv-script-meter-track">
          <div className="pv-script-meter-fill" style={{ width: `${pct}%` }} />
        </div>
      </div>
      <textarea
        id={id}
        rows={3}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-describedby={over ? 'script-length-warning' : undefined}
      />
    </div>
  );
}

interface ScriptEditorProps {
  runId: string;
  task: ReviewTaskResponse;
  revisions: ScriptRevisionResponse[];
  onApprovalBlockedChange: (blocked: boolean) => void;
  isMcTemplate?: boolean | undefined;
}

export function ScriptEditor({
  runId,
  task,
  revisions,
  onApprovalBlockedChange,
  isMcTemplate = false,
}: Readonly<ScriptEditorProps>) {
  const { t } = useTranslation();
  const latest = revisions.at(-1);
  const saved = latest?.content ?? '';
  const [draft, setDraft] = useState(saved);
  const [mcSegments, setMcSegments] = useState<[string, string]>(() => splitMcScript(saved));
  const save = useSaveRevisionMutation(runId, task.id ?? '');

  const effectiveDraft = isMcTemplate ? joinMcScript(mcSegments[0], mcSegments[1]) : draft;
  const normalizedSaved = isMcTemplate
    ? joinMcScript(...splitMcScript(saved))
    : saved.trim();
  const dirty = isMcTemplate
    ? effectiveDraft !== normalizedSaved
    : effectiveDraft.trim() !== normalizedSaved;
  const revisionIsCurrent = latest?.id === task.currentRevisionId && task.version !== undefined;
  const words = wordCount(effectiveDraft);
  const tooLong = words > MAX_SCRIPT_WORDS;
  const estimatedSeconds = Math.max(0, Math.round(words / 3));
  const meterPercent = Math.min(100, Math.round((words / MAX_SCRIPT_WORDS) * 100));

  const mcHookWords = wordCount(mcSegments[0]);
  const mcCloseWords = wordCount(mcSegments[1]);
  const mcHookOver = mcHookWords > MAX_MC_SEGMENT_WORDS;
  const mcCloseOver = mcCloseWords > MAX_MC_SEGMENT_WORDS;
  const mcMissingSegment =
    isMcTemplate && (!sanitizeMcSegment(mcSegments[0]) || !sanitizeMcSegment(mcSegments[1]));

  // Another tab can move the current revision while this one is open; adopting its text is what
  // makes the 409 below the answer, rather than this editor silently holding text nobody can approve.
  useEffect(() => {
    if (!dirty) {
      setDraft(saved);
      setMcSegments(splitMcScript(saved));
    }
  }, [saved, dirty]);

  useEffect(() => {
    onApprovalBlockedChange(dirty || save.isPending || !revisionIsCurrent || mcMissingSegment);
  }, [dirty, mcMissingSegment, onApprovalBlockedChange, revisionIsCurrent, save.isPending]);

  useWarnOnUnsavedChanges(dirty);

  const loadContent = (content: string) => {
    setDraft(content);
    setMcSegments(splitMcScript(content));
  };

  return (
    <div className="script-editor">
      <RevisionHistory
        revisions={revisions}
        approvedRevisionId={task.approvedRevisionId}
        onSelectRevision={(content) => {
          if (dirty && !window.confirm(t('workflow.script.replaceConfirm'))) {
            return;
          }
          loadContent(content);
        }}
      />
      {isMcTemplate ? (
        <div className="pv-mc-script-grid">
          <McSegmentField
            id="script-mc-hook"
            label={t('workflow.script.mcHookLabel')}
            value={mcSegments[0]}
            onChange={(val) => setMcSegments([val, mcSegments[1]])}
          />
          <McSegmentField
            id="script-mc-close"
            label={t('workflow.script.productCloseLabel')}
            value={mcSegments[1]}
            onChange={(val) => setMcSegments([mcSegments[0], val])}
          />
          <div aria-live="polite">
            {(tooLong || mcHookOver || mcCloseOver) && (
              <p id="script-length-warning" className="field-hint">
                {t('workflow.script.tooLong', { max: MAX_SCRIPT_WORDS })}
              </p>
            )}
          </div>
        </div>
      ) : (
        <div className="field">
          <label htmlFor="script-draft">{t('workflow.script.label')}</label>
          <textarea
            id="script-draft"
            rows={6}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            aria-describedby={tooLong ? 'script-length-warning' : undefined}
          />
          <div className={tooLong ? 'pv-script-meter pv-script-meter--over' : 'pv-script-meter'}>
            <span className="pv-script-meter-text">
              {t('workflow.script.meter', {
                words,
                max: MAX_SCRIPT_WORDS,
                seconds: estimatedSeconds,
              })}
            </span>
            <div className="pv-script-meter-track">
              <div className="pv-script-meter-fill" style={{ width: `${meterPercent}%` }} />
            </div>
          </div>
          {/* The live region stays mounted so the warning is announced when it appears. */}
          <div aria-live="polite">
            {tooLong && (
              <p id="script-length-warning" className="field-hint">
                {t('workflow.script.tooLong', { max: MAX_SCRIPT_WORDS })}
              </p>
            )}
          </div>
        </div>
      )}
      <button
        type="button"
        onClick={() => {
          if (!dirty || save.isPending || task.version === undefined || mcMissingSegment) return;
          save.mutate({ content: effectiveDraft, expectedVersion: task.version });
        }}
        disabled={!dirty || save.isPending || task.version === undefined || mcMissingSegment}
        aria-busy={save.isPending}
      >
        {save.isPending ? t('workflow.script.saving') : t('workflow.script.save')}
      </button>
      {mcMissingSegment && (
        <p role="alert" className="field-hint">
          {t('workflow.script.bothSegmentsRequired')}
        </p>
      )}
      {dirty && !mcMissingSegment && <p className="field-hint">{t('workflow.script.unsaved')}</p>}
      {save.isError && (
        <p role="alert" className="approval-error">
          {isLostRace(save.error)
            ? t('workflow.approval.scriptMovedOn')
            : t('workflow.script.saveFailed')}
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
  const { t } = useTranslation();
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
      if (!window.confirm(t('workflow.script.leaveConfirm'))) {
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
  }, [dirty, t]);
}

function RevisionHistory({
  revisions,
  approvedRevisionId,
  onSelectRevision,
}: Readonly<{
  revisions: ScriptRevisionResponse[];
  approvedRevisionId: string | undefined;
  onSelectRevision: (content: string) => void;
}>) {
  const { t } = useTranslation();
  if (revisions.length === 0) {
    return null;
  }
  return (
    <ol className="revision-history" aria-label={t('workflow.script.history')}>
      {revisions.map((revision) => (
        <li key={revision.id}>
          <button
            type="button"
            className="pv-revision-btn"
            aria-label={t('workflow.script.loadRevision', { number: revision.revisionNumber })}
            onClick={() => onSelectRevision(revision.content ?? '')}
          >
            <span className="pv-revision-head">
              <span className="revision-number">
                {t('workflow.script.revision', { number: revision.revisionNumber })}
              </span>
              {revision.id === approvedRevisionId && (
                <span className="revision-approved">{t('workflow.script.approved')}</span>
              )}
            </span>
            <span className="revision-preview">{revision.content}</span>
          </button>
        </li>
      ))}
    </ol>
  );
}
