import {
  authHeaders,
  createDriveItem,
  requestDriveUpload,
  unwrap,
  type DriveItemResponse,
} from '@flow-cortex/api-client';
import { UploadFailedError, uploadFile } from '../../workflow/uploadFile';
import type { ContentType } from '../model/driveRoles';

export interface UploadDriveItemInput {
  file: File;
  name: string;
  contentType: ContentType;
  semanticRole: string;
  onProgress: (percent: number) => void;
  /** Aborting stops the PUT; once the create is sent it cannot be stopped. */
  signal: AbortSignal;
  /** The PUT is done and the create is about to be sent: the point after which Cancel has no effect. */
  onSaving?: () => void;
}

/**
 * Asks for an upload grant, PUTs the file, then creates the item from the upload. Stops at the
 * first failure, so a refused grant sends no PUT and a failed or cancelled PUT sends no create.
 * A cancel is told from a failure by `signal.aborted`: both reject with `UploadFailedError(null)`.
 */
export async function uploadDriveItem({
  file,
  name,
  contentType,
  semanticRole,
  onProgress,
  signal,
  onSaving,
}: UploadDriveItemInput): Promise<DriveItemResponse> {
  const grant = await unwrap(requestDriveUpload({ fileName: file.name, size: file.size }, authHeaders()));
  await uploadFile(grant.url, file, grant.headers, onProgress, signal);
  // The PUT may finish in the same instant as a cancel; a cancelled upload must not become an item.
  if (signal.aborted) {
    throw new UploadFailedError(null);
  }
  onSaving?.();
  return unwrap(
    createDriveItem({ name, contentType, semanticRole, uploadId: grant.uploadId }, authHeaders()),
  );
}
