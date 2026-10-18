import { z } from 'zod';

/**
 * Mirrors `WorkflowsController.StartRunRequest` for a workflow whose first step runs inline —
 * `input` is optional: the seeded `echo` demo just returns whatever is given, including nothing.
 */
export const startRunSchema = z.object({
  input: z.string().optional(),
});

export type StartRunFormValues = z.infer<typeof startRunSchema>;

const MEGABYTE = 1024 * 1024;

/**
 * The server's default formats and caps per kind (`flowcortex.media.upload`), extensions in the
 * order the server lists them. Checked on pick only so a member hears about a wrong file before
 * uploading it; `/uploads` and the run start decide when an operator changed a cap.
 */
export interface UploadKind {
  extensions: readonly string[];
  maxBytes: number;
}

const VIDEO: UploadKind = { extensions: ['mp4', 'mov', 'webm', 'mkv'], maxBytes: 500 * MEGABYTE };
const AUDIO: UploadKind = { extensions: ['mp3', 'wav', 'm4a'], maxBytes: 100 * MEGABYTE };
export const IMAGE: UploadKind = {
  extensions: ['jpg', 'jpeg', 'png', 'webp'],
  maxBytes: 10 * MEGABYTE,
};
const MEDIA_KINDS: readonly UploadKind[] = [VIDEO, AUDIO];

/** A translation key with its values, so a problem already on screen follows a language switch. */
export interface FileProblem {
  messageKey: string;
  values?: Record<string, string | number>;
}

/** Lower-case extension of `fileName` without the dot, or `''` when it has none. */
export function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.');
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase();
}

/**
 * The server's `errors.size` sentence for `kind` (word for word in English) when `file` is empty
 * or over the kind's cap; exactly the cap is accepted.
 */
export function sizeProblem(file: File, kind: UploadKind): FileProblem | null {
  if (file.size >= 1 && file.size <= kind.maxBytes) {
    return null;
  }
  const formats = kind.extensions.map((extension) => `.${extension}`).join(', ');
  return {
    messageKey: 'workflow.validation.kindSize',
    values: { max: kind.maxBytes / MEGABYTE, formats },
  };
}

export const NO_FILE: FileProblem = { messageKey: 'workflow.validation.noFile' };

/** Why a picked file cannot be checked, or `null` when it may be uploaded. */
export function mediaFileProblem(file: File): FileProblem | null {
  const extension = extensionOf(file.name);
  const kind = MEDIA_KINDS.find((candidate) => candidate.extensions.includes(extension));
  if (!kind) {
    return { messageKey: 'workflow.validation.fileType' };
  }
  return sizeProblem(file, kind);
}
