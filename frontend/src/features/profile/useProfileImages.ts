import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addOriginal,
  authHeaders,
  current,
  originals,
  requestUpload1,
  setCurrent,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';
import type { ProfileImageView } from '@flow-cortex/api-client';

export type ImageSlot = 'avatar' | 'cover';

/** View URLs are signed; refetch a minute before the earliest one stops working. */
const REFRESH_BEFORE_EXPIRY_MS = 60_000;

function staleUntilExpiry(images: readonly (ProfileImageView | undefined)[]): number {
  const expiries = images
    .map((image) => (image?.expiresAt ? Date.parse(image.expiresAt) : Number.NaN))
    .filter((time) => !Number.isNaN(time));
  if (expiries.length === 0) return 0;
  return Math.max(0, Math.min(...expiries) - REFRESH_BEFORE_EXPIRY_MS - Date.now());
}

export function useProfileImagesQuery() {
  const token = useAuthToken();
  return useQuery({
    queryKey: ['profile-images', token],
    queryFn: () => unwrap(current(authHeaders())),
    enabled: Boolean(token),
    staleTime: (query) =>
      staleUntilExpiry([query.state.data?.avatar, query.state.data?.cover]),
  });
}

export function useOriginalsQuery(slot: ImageSlot, enabled = true) {
  const token = useAuthToken();
  return useQuery({
    queryKey: ['profile-image-originals', slot, token],
    queryFn: () => unwrap(originals(slot, authHeaders())),
    enabled: Boolean(token) && enabled,
    staleTime: (query) => staleUntilExpiry(query.state.data ?? []),
  });
}

/** Asks for a grant, then PUTs `file` to it; the signed URL is the credential, so no Authorization header. */
export async function uploadProfileImage(file: Blob, fileName: string): Promise<string> {
  const grant = await unwrap(requestUpload1({ fileName, size: file.size }, authHeaders()));
  const response = await fetch(grant.url, {
    method: grant.method,
    headers: grant.headers,
    body: file,
  });
  if (!response.ok) throw new Error(`The upload was refused with status ${response.status}`);
  return grant.uploadId;
}

function useRefreshingImages<V, R>(slot: ImageSlot, save: (variables: V) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: save,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['profile-images'] }),
        queryClient.invalidateQueries({ queryKey: ['profile-image-originals', slot] }),
      ]),
  });
}

/** Records an uploaded file as a history original of `slot`. */
export function useRecordOriginal(slot: ImageSlot) {
  return useRefreshingImages(slot, async (uploadId: string) =>
    unwrap(addOriginal(slot, { uploadId }, authHeaders())),
  );
}

/** Makes an uploaded (cropped) file the current image of `slot`. */
export function useSetCurrent(slot: ImageSlot) {
  return useRefreshingImages(slot, async (uploadId: string) =>
    unwrap(setCurrent(slot, { uploadId }, authHeaders())),
  );
}
