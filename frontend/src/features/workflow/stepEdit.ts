import { ApiError } from '@flow-cortex/api-client';

/** One text of a step's result the server lets the member change, with its current value. */
export interface EditableText {
  pointer: string;
  text: string;
}

/** What the edit panel shows for a refused or failed edit, undo or discard. */
export interface StepEditProblem {
  kind: 'field' | 'conflict' | 'notEditable' | 'gone' | 'retry';
  messageKey: string;
  /** For `field`: the index into the fields sent; `null` when the refusal names the list or the token. */
  fieldIndex: number | null;
  /** The server's sentence for the named field, shown in preference to `messageKey`. */
  detail: string | null;
}

const FIELD_ERROR_KEY = /^fields\[(\d+)\]\.(?:text|pointer)$/;
const REQUEST_ERROR_KEYS: readonly string[] = ['fields', 'expectedRevision'];

/** The longest text the server accepts per field label; the textarea stops there. */
const MAX_LENGTH: Record<string, number> = { output: 100_000, script: 8_000, line: 1_000 };

export function maxLengthOf(label: string): number | undefined {
  return MAX_LENGTH[label];
}

function fieldProblem(errors: Record<string, string> | undefined): StepEditProblem {
  for (const [key, detail] of Object.entries(errors ?? {})) {
    const index = FIELD_ERROR_KEY.exec(key)?.[1];
    if (index !== undefined) {
      return {
        kind: 'field',
        messageKey: 'workflow.edit.error.field',
        fieldIndex: Number(index),
        detail,
      };
    }
  }
  const request = Object.entries(errors ?? {}).find(([key]) => REQUEST_ERROR_KEYS.includes(key));
  return {
    kind: 'field',
    messageKey: 'workflow.edit.error.field',
    fieldIndex: null,
    detail: request?.[1] ?? null,
  };
}

function plain(kind: StepEditProblem['kind'], messageKey: string): StepEditProblem {
  return { kind, messageKey, fieldIndex: null, detail: null };
}

/**
 * Maps the status, problem code and field errors of a failed edit, undo or discard to what the
 * panel shows. Anything that is not a recognised refusal (network, 5xx, rate limit) is `retry`.
 */
export function stepEditProblem(
  status: number | undefined,
  code: string | undefined,
  errors: Record<string, string> | undefined,
): StepEditProblem {
  if (status === 400) {
    return fieldProblem(errors);
  }
  if (status === 404) {
    return plain('gone', 'workflow.edit.error.gone');
  }
  if (status === 409) {
    if (code === 'step_not_editable') {
      return plain('notEditable', 'workflow.edit.error.notEditable');
    }
    if (code === 'nothing_to_undo') {
      return plain('conflict', 'workflow.edit.error.nothingToUndo');
    }
    return plain('conflict', 'workflow.edit.error.conflict');
  }
  return plain('retry', 'workflow.edit.error.retry');
}

/** {@link stepEditProblem} over whatever a mutation threw. */
export function stepEditProblemOf(error: unknown): StepEditProblem {
  if (error instanceof ApiError) {
    return stepEditProblem(error.status, error.problem?.code, error.problem?.errors);
  }
  return stepEditProblem(undefined, undefined, undefined);
}

/**
 * The fields to send: those whose draft (keyed by pointer) differs from the current text, in
 * field order. Empty while nothing changed, which is when Save stays disabled.
 */
export function changedFields(
  fields: readonly EditableText[],
  drafts: Readonly<Record<string, string>>,
): EditableText[] {
  return fields.flatMap(({ pointer, text }) => {
    const draft = drafts[pointer];
    return draft !== undefined && draft !== text ? [{ pointer, text: draft }] : [];
  });
}
