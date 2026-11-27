import type { RunStepResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  changedFields,
  maxLengthOf,
  stepEditProblemOf,
  type EditableText,
  type StepEditProblem,
} from './stepEdit';
import { useStepEdits } from './useStepEdits';
import './RunExtras.css';

type EditableField = NonNullable<RunStepResponse['editableFields']>[number];

interface StepEditorProps {
  fields: readonly EditableField[];
  pending: boolean;
  /** A refusal that names the list or the revision, shown above the textareas. */
  listProblem: StepEditProblem | null;
  /** The textarea a refusal points at, and the refusal. */
  fieldProblem: { pointer: string; problem: StepEditProblem } | null;
  onSave: (fields: EditableText[]) => void;
  onCancel: () => void;
}

function problemText(problem: StepEditProblem, t: TFunction) {
  return problem.detail ?? t(problem.messageKey);
}

/** One labelled textarea per editable text, seeded with its current value. */
function StepEditor({
  fields,
  pending,
  listProblem,
  fieldProblem,
  onSave,
  onCancel,
}: Readonly<StepEditorProps>) {
  const { t } = useTranslation();
  const idBase = useId();
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const firstRef = useRef<HTMLTextAreaElement>(null);
  const changed = changedFields(fields, drafts);

  useEffect(() => {
    firstRef.current?.focus();
  }, []);

  return (
    <form
      className="step-edit-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (changed.length > 0 && !pending) onSave(changed);
      }}
    >
      {listProblem && (
        <p role="alert" className="step-edit-error">
          {problemText(listProblem, t)}
        </p>
      )}
      {fields.map((field, i) => {
        const id = `${idBase}-${i}`;
        const sourceId = `${id}-source`;
        const errorId = `${id}-error`;
        const failed = fieldProblem?.pointer === field.pointer ? fieldProblem.problem : null;
        const describedBy = [field.source ? sourceId : null, failed ? errorId : null]
          .filter(Boolean)
          .join(' ');
        return (
          <div key={field.pointer} className="step-edit-field">
            <label htmlFor={id}>
              {t(`workflow.edit.field.${field.label}`, {
                number: i + 1,
                defaultValue: field.label,
              })}
            </label>
            {field.source && (
              <p id={sourceId} className="step-edit-source">
                {field.source}
              </p>
            )}
            <textarea
              id={id}
              ref={i === 0 ? firstRef : undefined}
              rows={field.label === 'line' ? 2 : 8}
              maxLength={maxLengthOf(field.label)}
              value={drafts[field.pointer] ?? field.text}
              onChange={(event) =>
                setDrafts((current) => ({ ...current, [field.pointer]: event.target.value }))
              }
              readOnly={pending}
              aria-invalid={failed ? true : undefined}
              aria-describedby={describedBy || undefined}
            />
            {failed && (
              <p id={errorId} role="alert" className="step-edit-error">
                {problemText(failed, t)}
              </p>
            )}
          </div>
        );
      })}
      <div className="step-edit-actions">
        <button type="submit" disabled={changed.length === 0 || pending} aria-busy={pending}>
          {pending ? t('workflow.edit.saving') : t('workflow.edit.save')}
        </button>
        <button type="button" onClick={onCancel} disabled={pending}>
          {t('workflow.edit.cancel')}
        </button>
      </div>
    </form>
  );
}

interface DiscardDialogProps {
  open: boolean;
  discarding: boolean;
  onKeep: () => void;
  onDiscard: () => void;
}

/**
 * The confirmation before an edit is dropped, as a native modal dialog: focus starts on "Keep
 * edit", Escape means "Keep edit", and focus returns to whatever opened it.
 */
function DiscardEditDialog({ open, discarding, onKeep, onDiscard }: Readonly<DiscardDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const keepRef = useRef<HTMLButtonElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
        keepRef.current?.focus();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      className="step-edit-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        if (!discarding) onKeep();
      }}
      onClose={() => {
        if (open) onKeep();
      }}
    >
      <h2 id={titleId}>{t('workflow.edit.confirm.title')}</h2>
      <p id={textId}>{t('workflow.edit.confirm.text')}</p>
      <div className="step-edit-dialog-actions">
        <button
          ref={keepRef}
          type="button"
          onClick={() => {
            if (!discarding) onKeep();
          }}
          aria-disabled={discarding}
        >
          {t('workflow.edit.confirm.keep')}
        </button>
        <button
          type="button"
          className="step-edit-discard-confirm"
          onClick={() => {
            if (!discarding) onDiscard();
          }}
          aria-disabled={discarding}
          aria-busy={discarding}
        >
          {discarding ? t('workflow.edit.discarding') : t('workflow.edit.confirm.discard')}
        </button>
      </div>
    </dialog>
  );
}

interface StepEditPanelProps {
  runId: string;
  step: RunStepResponse;
}

/**
 * Edit, undo and discard for a step's result. What may be edited comes from the server's
 * `editable` and `editableFields`; the panel only renders them. It stays mounted after a
 * refusal that makes the step not editable, so the message is not lost with the re-read.
 */
export function StepEditPanel({ runId, step }: Readonly<StepEditPanelProps>) {
  const { t } = useTranslation();
  const stepId = step.id ?? '';
  const { save, undo, discard } = useStepEdits(runId, stepId);
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const editRef = useRef<HTMLButtonElement>(null);
  const edited = typeof step.editedOutput === 'string';
  const wasEdited = useRef(edited);
  const busy = save.isPending || undo.isPending || discard.isPending;
  const failed = [save, undo, discard].find((mutation) => mutation.isError);
  const problem = failed ? stepEditProblemOf(failed.error) : null;

  // Undo or discard removes the button that had focus; keep focus inside the panel.
  useEffect(() => {
    const active = document.activeElement;
    if (wasEdited.current && !edited && (!active || active === document.body)) {
      editRef.current?.focus();
    }
    wasEdited.current = edited;
  }, [edited]);

  if (!step.id || (!step.editable && !problem)) {
    return null;
  }

  const resetAll = () => {
    save.reset();
    undo.reset();
    discard.reset();
  };
  const sentPointer =
    problem?.kind === 'field' && problem.fieldIndex !== null && failed === save
      ? save.variables?.fields[problem.fieldIndex]?.pointer
      : undefined;
  const retry = () => {
    if (failed === save && save.variables) {
      save.mutate(save.variables, { onSuccess: () => setEditing(false) });
    } else if (failed === undo) {
      undo.mutate();
    } else if (failed === discard) {
      discard.mutate();
    }
  };
  const inEditor = editing && step.editable;

  return (
    <section className="step-edit" aria-label={t('workflow.edit.open')}>
      {step.editable && (
        <div className="step-edit-bar">
          {edited && (
            <span className="step-edit-mark">
              {t('workflow.edit.edited', { revision: step.editRevision })}
            </span>
          )}
          {!inEditor && (
            <>
              <button
                ref={editRef}
                type="button"
                disabled={busy}
                onClick={() => {
                  resetAll();
                  setEditing(true);
                }}
              >
                {t('workflow.edit.open')}
              </button>
              {edited && (
                <>
                  <button
                    type="button"
                    disabled={busy}
                    aria-busy={undo.isPending}
                    onClick={() => {
                      resetAll();
                      undo.mutate();
                    }}
                  >
                    {undo.isPending ? t('workflow.edit.undoing') : t('workflow.edit.undo')}
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => {
                      resetAll();
                      setConfirming(true);
                    }}
                  >
                    {t('workflow.edit.discard')}
                  </button>
                </>
              )}
            </>
          )}
        </div>
      )}
      {inEditor && (
        <StepEditor
          key={step.editRevision}
          fields={step.editableFields}
          pending={save.isPending}
          listProblem={problem?.kind === 'field' && sentPointer === undefined ? problem : null}
          fieldProblem={
            problem && sentPointer !== undefined ? { pointer: sentPointer, problem } : null
          }
          onSave={(fields) => {
            resetAll();
            save.mutate(
              { fields, expectedRevision: step.editRevision },
              { onSuccess: () => setEditing(false) },
            );
          }}
          onCancel={() => {
            resetAll();
            setEditing(false);
          }}
        />
      )}
      {problem && !(inEditor && problem.kind === 'field') && (
        <div className="step-edit-problem">
          <p role="alert" className="step-edit-error">
            {t(problem.messageKey)}
          </p>
          {problem.kind === 'retry' && (
            <button type="button" disabled={busy} onClick={retry}>
              {t('workflow.edit.retry')}
            </button>
          )}
        </div>
      )}
      <DiscardEditDialog
        open={confirming}
        discarding={discard.isPending}
        onKeep={() => setConfirming(false)}
        onDiscard={() =>
          discard.mutate(undefined, {
            onSuccess: () => setConfirming(false),
            onError: () => setConfirming(false),
          })
        }
      />
    </section>
  );
}
