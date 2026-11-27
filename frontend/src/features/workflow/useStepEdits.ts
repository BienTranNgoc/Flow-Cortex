import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  discardRunStepEdit,
  editRunStepOutput,
  undoRunStepEdit,
  unwrap,
} from '@flow-cortex/api-client';
import type { EditableText } from './stepEdit';

export interface SaveStepEdit {
  fields: EditableText[];
  /** The step's `editRevision` as loaded. */
  expectedRevision: number;
}

/**
 * Save, undo and discard for one step. Each re-reads the run on success; a refusal the client
 * can act on (a stale revision, a step no longer editable or gone) re-reads it too, so the panel
 * shows the current text beside the message.
 */
export function useStepEdits(runId: string, stepId: string) {
  const queryClient = useQueryClient();
  const reread = () => queryClient.invalidateQueries({ queryKey: ['runs', runId], exact: true });
  const rereadOnRefusal = (error: unknown) =>
    error instanceof ApiError && error.status >= 400 && error.status < 500 ? reread() : undefined;
  const options = { onSuccess: reread, onError: rereadOnRefusal };

  const save = useMutation({
    mutationFn: ({ fields, expectedRevision }: SaveStepEdit) =>
      unwrap(
        editRunStepOutput(
          runId,
          stepId,
          { fields, expectedRevision },
          authHeaders(),
        ),
      ),
    ...options,
  });
  const undo = useMutation({
    mutationFn: () => unwrap(undoRunStepEdit(runId, stepId, authHeaders())),
    ...options,
  });
  const discard = useMutation({
    mutationFn: () => unwrap(discardRunStepEdit(runId, stepId, authHeaders())),
    ...options,
  });
  return { save, undo, discard };
}
