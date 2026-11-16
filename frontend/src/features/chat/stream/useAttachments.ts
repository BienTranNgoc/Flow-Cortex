import { useCallback, useMemo, useRef, useState } from 'react';
import { imageFileProblem } from '../../workflow/productVideoUpload';
import { startProblem } from '../../workflow/startProblem';
import { uploadFile } from '../../workflow/uploadFile';
import { useCreateUploadMutation } from '../../workflow/useWorkflows';

/** Why an attachment is not going to be sent; a key is translated at render so a language switch follows. */
export type AttachmentProblem =
  | { kind: 'key'; messageKey: string; values?: Record<string, string | number> | undefined }
  | { kind: 'text'; message: string }
  | { kind: 'failed' };

export interface AttachmentItem {
  localId: number;
  fileName: string;
  status: 'uploading' | 'ready' | 'problem';
  uploadId?: string;
  problem?: AttachmentProblem;
}

export interface Attachments {
  items: AttachmentItem[];
  /** The uploads ready to send. */
  ready: { uploadId: string; fileName: string }[];
  uploading: boolean;
  /** More files were picked than `max` allows; those were left out. */
  overflow: boolean;
  add: (files: readonly File[]) => void;
  remove: (localId: number) => void;
  clear: () => void;
}

/**
 * Uploads each picked image at once through the upload grant, so a send or confirm that has to be
 * retried keeps the same upload ids. A file the run form would refuse is shown with that message
 * and never uploaded.
 */
export function useAttachments(max: number): Attachments {
  const requestUpload = useCreateUploadMutation().mutateAsync;
  const [items, setItems] = useState<AttachmentItem[]>([]);
  const [overflow, setOverflow] = useState(false);
  const nextId = useRef(0);
  const itemsRef = useRef(items);
  itemsRef.current = items;

  const patch = useCallback((localId: number, change: Partial<AttachmentItem>) => {
    setItems((current) => current.map((item) => (item.localId === localId ? { ...item, ...change } : item)));
  }, []);

  const upload = useCallback(
    async (localId: number, file: File) => {
      try {
        const grant = await requestUpload({ fileName: file.name, size: file.size });
        await uploadFile(grant.url, file, grant.headers, () => undefined);
        patch(localId, { status: 'ready', uploadId: grant.uploadId });
      } catch (error) {
        const problem = startProblem(error);
        patch(localId, {
          status: 'problem',
          problem: problem.kind === 'field' ? { kind: 'text', message: problem.message } : { kind: 'failed' },
        });
      }
    },
    [patch, requestUpload],
  );

  const add = useCallback(
    (files: readonly File[]) => {
      let accepted = itemsRef.current.filter((item) => item.status !== 'problem').length;
      const added: AttachmentItem[] = [];
      let tooMany = false;
      for (const file of files) {
        const problem = imageFileProblem(file);
        if (problem) {
          added.push({
            localId: nextId.current++,
            fileName: file.name,
            status: 'problem',
            problem: { kind: 'key', messageKey: problem.messageKey, values: problem.values },
          });
        } else if (accepted >= max) {
          tooMany = true;
        } else {
          accepted += 1;
          const localId = nextId.current++;
          added.push({ localId, fileName: file.name, status: 'uploading' });
          void upload(localId, file);
        }
      }
      setOverflow(tooMany);
      setItems((current) => [...current, ...added]);
    },
    [max, upload],
  );

  const remove = useCallback((localId: number) => {
    setItems((current) => current.filter((item) => item.localId !== localId));
    setOverflow(false);
  }, []);

  const clear = useCallback(() => {
    setItems([]);
    setOverflow(false);
  }, []);

  return useMemo(
    () => ({
      items,
      ready: items.flatMap((item) =>
        item.status === 'ready' && item.uploadId ? [{ uploadId: item.uploadId, fileName: item.fileName }] : [],
      ),
      uploading: items.some((item) => item.status === 'uploading'),
      overflow,
      add,
      remove,
      clear,
    }),
    [items, overflow, add, remove, clear],
  );
}
