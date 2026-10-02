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
 */
export function uploadFile(
  url: string,
  file: File,
  headers: Record<string, string>,
  onProgress: (percent: number) => void,
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
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new UploadFailedError(xhr.status));
      }
    };
    xhr.onerror = () => reject(new UploadFailedError(null));
    xhr.onabort = () => reject(new UploadFailedError(null));
    xhr.ontimeout = () => reject(new UploadFailedError(null));
    xhr.send(file);
  });
}
