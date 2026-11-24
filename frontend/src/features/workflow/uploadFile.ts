/** The store refused the PUT (`status`) or it never completed (`null`: network error, abort, timeout). */
export class UploadFailedError extends Error {
  readonly status: number | null;

  constructor(status: number | null) {
    super(status === null ? 'Upload did not complete' : `Upload refused with status ${status}`);
    this.name = 'UploadFailedError';
    this.status = status;
  }
}

/**
 * PUTs `file` to a presigned URL with exactly `headers` — the URL is the credential, so no
 * Authorization header is sent. XHR rather than `fetch` because only XHR reports upload progress.
 * Aborting `signal` stops the PUT and rejects with `UploadFailedError(null)`.
 */
export function uploadFile(
  url: string,
  file: File,
  headers: Record<string, string>,
  onProgress: (percent: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    for (const [name, value] of Object.entries(headers)) {
      xhr.setRequestHeader(name, value);
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    const abort = () => xhr.abort();
    const settle = (error: UploadFailedError | null) => {
      signal?.removeEventListener('abort', abort);
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };
    xhr.onload = () => {
      settle(xhr.status >= 200 && xhr.status < 300 ? null : new UploadFailedError(xhr.status));
    };
    xhr.onerror = () => settle(new UploadFailedError(null));
    xhr.onabort = () => settle(new UploadFailedError(null));
    xhr.ontimeout = () => settle(new UploadFailedError(null));
    xhr.send(file);
    if (signal?.aborted) {
      abort();
    } else {
      signal?.addEventListener('abort', abort, { once: true });
    }
  });
}
