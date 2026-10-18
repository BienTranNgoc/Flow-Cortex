import type { UploadRequest, UploadResponse } from '@flow-cortex/api-client';

export interface MultiUploadDeps {
  requestUpload: (body: UploadRequest) => Promise<UploadResponse>;
  uploadFile: (
    url: string,
    file: File,
    headers: Record<string, string>,
    onProgress: (percent: number) => void,
  ) => Promise<void>;
}

export type MultiUploadPhase =
  | { kind: 'requesting' }
  | { kind: 'uploading'; percent: number; index: number; total: number }
  | { kind: 'done' };

/** Why a picked image cannot be checked, or `null` when it may be uploaded. */
const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'webp']);
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MIN_IMAGES = 1;
const MAX_IMAGES = 10;

/** A translation key with its values, so a problem already on screen follows a language switch. */
export interface ImageProblem {
  messageKey: string;
  values?: Record<string, number>;
}

export function imageFileProblem(file: File): ImageProblem | null {
  if (file.size < 1 || file.size > MAX_IMAGE_BYTES) {
    return { messageKey: 'workflow.validation.imageSize' };
  }
  const dot = file.name.lastIndexOf('.');
  const extension = dot < 0 ? '' : file.name.slice(dot + 1).toLowerCase();
  if (!IMAGE_EXTENSIONS.has(extension)) {
    return { messageKey: 'workflow.validation.imageType' };
  }
  return null;
}

export const EMPTY_IMAGE_LIST: ImageProblem = {
  messageKey: 'workflow.validation.imageCount',
  values: { min: MIN_IMAGES, max: MAX_IMAGES },
};

export const TOO_MANY_IMAGES: ImageProblem = {
  messageKey: 'workflow.validation.tooManyImages',
  values: { max: MAX_IMAGES },
};

/** Walks `files`; returns the first ImageProblem found or null. */
export function firstImageProblem(files: File[]): ImageProblem | null {
  if (files.length < MIN_IMAGES) {
    return EMPTY_IMAGE_LIST;
  }
  if (files.length > MAX_IMAGES) {
    return TOO_MANY_IMAGES;
  }
  for (const file of files) {
    const problem = imageFileProblem(file);
    if (problem) {
      return problem;
    }
  }
  return null;
}

/** Why a multi-image upload failed; `stage` is the boundary that refused, `cause` its error. */
export type MultiUploadStage = 'request' | 'upload';

export class MultiUploadError extends Error {
  readonly stage: MultiUploadStage;
  readonly index: number;
  override readonly cause: unknown;

  constructor(stage: MultiUploadStage, index: number, cause: unknown) {
    super(`Multi-upload failed at stage ${stage} (image ${index + 1})`);
    this.name = 'MultiUploadError';
    this.stage = stage;
    this.index = index;
    this.cause = cause;
  }
}

/**
 * Uploads every file sequentially (XHR has no per-file cancellation, so serial keeps the progress
 * bar honest and the request rate well-behaved). Stops on the first failure: no upload id is
 * returned, so the form cannot start a run referencing an object that never landed.
 */
export function createMultiUpload(deps: MultiUploadDeps) {
  let inFlight = false;

  return async function uploadAll(
    files: File[],
    onPhase: (phase: MultiUploadPhase) => void,
  ): Promise<string[] | null> {
    if (inFlight) {
      return null;
    }
    inFlight = true;
    try {
      const uploadIds: string[] = [];
      for (let index = 0; index < files.length; index += 1) {
        const file = files[index]!;
        let grant: UploadResponse;
        try {
          onPhase({ kind: 'requesting' });
          grant = await deps.requestUpload({ fileName: file.name, size: file.size });
        } catch (cause) {
          throw new MultiUploadError('request', index, cause);
        }
        try {
          await deps.uploadFile(grant.url, file, grant.headers, (percent) =>
            onPhase({ kind: 'uploading', percent, index, total: files.length }),
          );
        } catch (cause) {
          throw new MultiUploadError('upload', index, cause);
        }
        uploadIds.push(grant.uploadId);
      }
      onPhase({ kind: 'done' });
      return uploadIds;
    } finally {
      inFlight = false;
    }
  };
}
