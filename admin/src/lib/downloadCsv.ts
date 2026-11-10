import { ApiError, getToken, sessionFetch, type ProblemDetail } from '@flow-cortex/api-client';

export interface CsvDownload {
  fileName: string;
  /** `X-Total-Count`: the rows that matched, of which the file holds at most the export cap. */
  total: number | undefined;
}

const FALLBACK_NAME = 'export.csv';

function fileNameOf(disposition: string | null): string {
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition ?? '')?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      // A malformed escape falls through to the plain name.
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition ?? '')?.[1];
  return plain ?? FALLBACK_NAME;
}

async function problemOf(response: Response): Promise<ProblemDetail | undefined> {
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return undefined;
  }
}

/**
 * Fetches a CSV export with the caller's bearer token and saves it through a temporary link.
 * Throws {@link ApiError} for a non-2xx answer; a 401 goes through the shared refresh-and-replay first.
 */
export async function downloadCsv(url: string): Promise<CsvDownload> {
  const token = getToken();
  const response = await sessionFetch(url, {
    headers: { Accept: 'text/csv', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!response.ok) {
    throw new ApiError(response.status, await problemOf(response));
  }
  const blob = await response.blob();
  const fileName = fileNameOf(response.headers.get('Content-Disposition'));
  const totalHeader = response.headers.get('X-Total-Count');
  const total = totalHeader === null || totalHeader.trim() === '' ? Number.NaN : Number(totalHeader);

  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href;
  link.download = fileName;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(href);

  return { fileName, total: Number.isFinite(total) ? total : undefined };
}
