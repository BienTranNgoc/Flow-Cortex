import { useState } from 'react';

/** Whether the current `url` failed to load; a new url (a refreshed signed link or image) starts clean. */
export function useImageFailure(url: string | undefined) {
  const [failedUrl, setFailedUrl] = useState<string>();
  return {
    shown: Boolean(url) && failedUrl !== url,
    onError: () => setFailedUrl(url),
  };
}
