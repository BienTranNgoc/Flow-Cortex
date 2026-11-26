import {
  ApiError,
  authHeaders,
  downloadFile,
  getBillingReceipt,
  getDownloadBillingReceiptPdfUrl,
  unwrap,
  type BillingReceiptResponse,
} from '@flow-cortex/api-client';
import { useMutation, useQuery } from '@tanstack/react-query';

const DOWNLOAD_FALLBACK_NAME = 'receipt.pdf';

export type ReceiptLookup =
  | { readonly kind: 'found'; readonly receipt: BillingReceiptResponse }
  | { readonly kind: 'notFound' }
  | { readonly kind: 'notCollected' };

/** A 404 and a 409 are answers ("not yours or not there", "not collected yet"), so they resolve, not throw. */
export function useReceipt(id: string) {
  return useQuery({
    queryKey: ['billing', 'receipt', id],
    queryFn: async (): Promise<ReceiptLookup> => {
      try {
        const receipt = await unwrap(getBillingReceipt(id, authHeaders()));
        return { kind: 'found', receipt };
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return { kind: 'notFound' };
        if (error instanceof ApiError && error.status === 409) return { kind: 'notCollected' };
        throw error;
      }
    },
  });
}

function saveBlob(blob: Blob, fileName: string) {
  const objectUrl = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

/** Saves the receipt PDF under the server's file name; a refusal is thrown as its {@link ApiError}. */
export function useDownloadReceipt(id: string) {
  return useMutation({
    mutationFn: async () => {
      const result = await downloadFile(getDownloadBillingReceiptPdfUrl(id), {
        headers: {
          ...(authHeaders().headers as Record<string, string> | undefined),
          Accept: 'application/pdf, application/problem+json',
        },
      });
      if (result.blob === undefined) {
        throw new ApiError(result.status, result.problem);
      }
      saveBlob(result.blob, result.fileName ?? DOWNLOAD_FALLBACK_NAME);
    },
  });
}
