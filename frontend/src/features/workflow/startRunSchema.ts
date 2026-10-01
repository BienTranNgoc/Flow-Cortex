import { z } from 'zod';

/**
 * Mirrors `WorkflowsController.StartRunRequest` for a workflow whose first step runs inline —
 * `input` is optional: the seeded `echo` demo just returns whatever is given, including nothing.
 */
export const startRunSchema = z.object({
  input: z.string().optional(),
});

export type StartRunFormValues = z.infer<typeof startRunSchema>;

/**
 * The server's defaults for uploads (`flowcortex.media.upload`). Checked on pick only so a member
 * hears about a wrong file before uploading it; `/uploads` and the run start decide.
 */
const MEDIA_EXTENSIONS = ['mp4', 'mov', 'webm', 'mkv', 'm4a', 'mp3', 'wav'];
// Mirrors FLOWCORTEX_MEDIA_UPLOAD_MAX_BYTES (default 524288000); when it changes, the server decides.
const MAX_MEDIA_BYTES = 500 * 1024 * 1024;

export interface FileProblem {
  message: string;
  nextAction?: string;
}

export const NO_FILE: FileProblem = { message: 'Choose a video or audio file to upload' };

/** Why a picked file cannot be checked, or `null` when it may be uploaded. */
export function mediaFileProblem(file: File): FileProblem | null {
  if (file.size < 1 || file.size > MAX_MEDIA_BYTES) {
    return {
      message: 'Files must be between 1 byte and 500 MB.',
      nextAction: 'Choose a smaller file.',
    };
  }
  const dot = file.name.lastIndexOf('.');
  const extension = dot < 0 ? '' : file.name.slice(dot + 1).toLowerCase();
  if (!MEDIA_EXTENSIONS.includes(extension)) {
    return {
      message:
        "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.",
    };
  }
  return null;
}
