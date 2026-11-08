import {
  authHeaders,
  requestSupportUpload,
  type SupportUploadGrant,
  type SupportUploadRequest,
} from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import i18n from '../../lib/i18n';
import { uploadFile } from '../workflow/uploadFile';
import { unwrapSupport } from './useSupportKb';

export const MAX_ATTACHMENTS = 3;
export const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
export const ATTACHMENT_ACCEPT = 'image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp';

const TYPES_BY_EXTENSION: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
};

/** The upload grant refuses a longer `fileName` (counted in UTF-16 units, as Java does). */
const MAX_FILE_NAME_LENGTH = 255;

/** Why `file` cannot be attached, or `null` when it may be uploaded. */
export function attachmentProblem(file: File, t: TFunction = i18n.t): string | null {
  const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
  if (TYPES_BY_EXTENSION[extension] !== file.type) return t('support.requests.attachType');
  if (file.size < 1) return t('support.requests.attachEmpty');
  if (file.size > MAX_ATTACHMENT_BYTES) return t('support.requests.attachSize');
  if (file.name.length > MAX_FILE_NAME_LENGTH) return t('support.requests.attachNameLength');
  return null;
}

/** `current` plus every acceptable picked file that still fits the per-message cap; `refused` holds each distinct reason. */
export function addAttachments(
  current: readonly File[],
  picked: readonly File[],
  t: TFunction = i18n.t,
): { files: File[]; refused: string[] } {
  const files = [...current];
  const refused = new Set<string>();
  for (const file of picked) {
    const problem = attachmentProblem(file, t);
    if (problem) refused.add(problem);
    else if (files.length >= MAX_ATTACHMENTS) refused.add(t('support.requests.attachCount'));
    else files.push(file);
  }
  return { files, refused: [...refused] };
}

export interface UploadDeps {
  requestGrant: (body: SupportUploadRequest) => Promise<SupportUploadGrant>;
  put: (grant: SupportUploadGrant, file: File) => Promise<void>;
}

const liveDeps: UploadDeps = {
  requestGrant: (body) => unwrapSupport(requestSupportUpload(body, authHeaders())),
  put: (grant, file) => uploadFile(grant.url, file, grant.headers, () => {}),
};

/** Grant → PUT for each file in turn; resolves the upload ids in file order, rejects on the first failure. */
export async function uploadAttachments(
  files: readonly File[],
  deps: UploadDeps = liveDeps,
): Promise<string[]> {
  const ids: string[] = [];
  for (const file of files) {
    const grant = await deps.requestGrant({ fileName: file.name, size: file.size });
    await deps.put(grant, file);
    ids.push(grant.uploadId);
  }
  return ids;
}
