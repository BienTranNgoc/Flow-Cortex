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

/** The server's `errors.size` sentence for `kind`, word for word. */
function sizeSentence(kind: UploadKind): string {
  const formats = kind.extensions.map((extension) => `.${extension}`).join(', ');
  return `Files of this type must be at most ${kind.maxBytes / MEGABYTE} MB. Accepted: ${formats}.`;
}

/** Lower-case extension of `fileName` without the dot, or `''` when it has none. */
export function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.');
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase();
}

/** The kind's sentence when `file` is empty or over the kind's cap; exactly the cap is accepted. */
export function sizeProblem(file: File, kind: UploadKind): string | null {
  return file.size < 1 || file.size > kind.maxBytes ? sizeSentence(kind) : null;
}

export interface FileProblem {
  message: string;
}

export const NO_FILE: FileProblem = { message: 'Choose a video or audio file to upload' };

/** Why a picked file cannot be checked, or `null` when it may be uploaded. */
export function mediaFileProblem(file: File): FileProblem | null {
  const extension = extensionOf(file.name);
  const kind = MEDIA_KINDS.find((candidate) => candidate.extensions.includes(extension));
  if (!kind) {
    return {
      message:
        "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.",
    };
  }
  const tooLarge = sizeProblem(file, kind);
  return tooLarge ? { message: tooLarge } : null;
}
