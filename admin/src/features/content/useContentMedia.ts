import {
  authHeaders,
  listContentMedia,
  registerContentMedia,
  requestContentMediaUpload,
  type UploadGrant,
} from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapContent } from './contentProblems';

export const MEDIA_PAGE_SIZE = 24;

export type MediaFilter = '' | 'image' | 'video';

const MEDIA_KEY = ['admin', 'content', 'media'] as const;

/** One page of the library, newest first; an empty filter lists images and videos together. */
export function useMediaQuery(filter: MediaFilter, page: number) {
  return useQuery({
    queryKey: [...MEDIA_KEY, filter, page],
    queryFn: () =>
      unwrapContent(
        listContentMedia(
          { page, size: MEDIA_PAGE_SIZE, ...(filter ? { kind: filter } : {}) },
          authHeaders(),
        ),
      ),
    placeholderData: keepPreviousData,
  });
}

/** The store refused the PUT (`status`) or it never completed (`null`). */
export class UploadFailedError extends Error {
  readonly status: number | null;

  constructor(status: number | null) {
    super(status === null ? 'Upload did not complete' : `Upload refused with status ${status}`);
    this.name = 'UploadFailedError';
    this.status = status;
  }
}

/** PUTs `file` to the presigned URL with exactly the granted headers: the URL is the credential. */
function putFile(grant: UploadGrant, file: File, onProgress: (percent: number) => void) {
  return new Promise<void>((resolve, reject) => {
    if (!grant.url) {
      reject(new UploadFailedError(null));
      return;
    }
    const xhr = new XMLHttpRequest();
    xhr.open(grant.method || 'PUT', grant.url);
    for (const [name, value] of Object.entries(grant.headers ?? {})) {
      xhr.setRequestHeader(name, value);
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () =>
      xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new UploadFailedError(xhr.status));
    xhr.onerror = () => reject(new UploadFailedError(null));
    xhr.onabort = () => reject(new UploadFailedError(null));
    xhr.ontimeout = () => reject(new UploadFailedError(null));
    xhr.send(file);
  });
}

export interface UploadRequest {
  file: File;
  onProgress: (percent: number) => void;
}

/** Grant, PUT with progress, then register: the item exists in the library only after the last step. */
export function useUploadMedia() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ file, onProgress }: UploadRequest) => {
      const grant = await unwrapContent(
        requestContentMediaUpload({ fileName: file.name, size: file.size }, authHeaders()),
      );
      if (!grant.uploadId) throw new UploadFailedError(null);
      await putFile(grant, file, onProgress);
      return unwrapContent(
        registerContentMedia({ uploadId: grant.uploadId, fileName: file.name }, authHeaders()),
      );
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: MEDIA_KEY }),
  });
}
