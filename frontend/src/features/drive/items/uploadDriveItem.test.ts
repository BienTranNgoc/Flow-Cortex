import { ApiError } from '@flow-cortex/api-client';
import { waitFor } from '@testing-library/react';
import { stubApi } from '../../../test/fakeApi';
import { FakeXhr } from '../../../test/fakeXhr';
import { uploadDriveItem } from './uploadDriveItem';

const GRANT = {
  uploadId: 'up-1',
  url: 'http://localhost:9000/flowcortex-uploads/u/up-1?X-Amz-Signature=abc',
  method: 'PUT',
  headers: { 'Content-Type': 'image/png' },
  expiresAt: '2026-11-10T10:15:00Z',
  maxBytes: 10485760,
};

const ITEM = {
  id: 'item-1',
  name: 'Logo',
  contentType: 'image',
  semanticRole: 'logo',
  source: 'upload',
  createdAt: '2026-11-10T10:00:00Z',
  updatedAt: '2026-11-10T10:00:00Z',
};

function logo() {
  return new File(['0123456789'], 'logo.png', { type: 'image/png' });
}

function input(signal: AbortSignal, onProgress: (percent: number) => void = () => {}) {
  return {
    file: logo(),
    name: 'Logo',
    contentType: 'image' as const,
    semanticRole: 'logo',
    onProgress,
    signal,
  };
}

const sentPaths = (calls: { method: string; path: string }[]) =>
  calls.map((call) => `${call.method} ${call.path}`);

describe('uploadDriveItem', () => {
  beforeEach(() => {
    FakeXhr.instances.length = 0;
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('creates the item only after the PUT succeeds', async () => {
    const calls = stubApi({
      'POST /api/v1/drive/uploads': { status: 201, body: GRANT },
      'POST /api/v1/drive/items': { status: 201, body: ITEM },
    });
    const progress: number[] = [];
    const done = uploadDriveItem(input(new AbortController().signal, (percent) => progress.push(percent)));

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    const xhr = FakeXhr.instances[0]!;
    expect(xhr.url).toBe(GRANT.url);
    expect(xhr.headers).toEqual({ 'Content-Type': 'image/png' });
    expect(sentPaths(calls)).toEqual(['POST /api/v1/drive/uploads']);
    expect(calls[0]?.body).toEqual({ fileName: 'logo.png', size: 10 });

    xhr.progress(5, 10);
    xhr.respond(200);

    await expect(done).resolves.toEqual(ITEM);
    expect(progress).toEqual([50]);
    expect(sentPaths(calls)).toEqual(['POST /api/v1/drive/uploads', 'POST /api/v1/drive/items']);
    expect(calls[1]?.body).toEqual({
      name: 'Logo',
      contentType: 'image',
      semanticRole: 'logo',
      uploadId: 'up-1',
    });
  });

  it('sends no create when the PUT is refused', async () => {
    const calls = stubApi({
      'POST /api/v1/drive/uploads': { status: 201, body: GRANT },
      'POST /api/v1/drive/items': { status: 201, body: ITEM },
    });
    const done = uploadDriveItem(input(new AbortController().signal));
    const failed = expect(done).rejects.toMatchObject({ name: 'UploadFailedError', status: 403 });

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    FakeXhr.instances[0]!.respond(403);

    await failed;
    expect(sentPaths(calls)).toEqual(['POST /api/v1/drive/uploads']);
  });

  it('sends no PUT and no create when the upload grant answers 503', async () => {
    const calls = stubApi({
      'POST /api/v1/drive/uploads': {
        status: 503,
        body: { status: 503, detail: 'Drive is turned off' },
      },
      'POST /api/v1/drive/items': { status: 201, body: ITEM },
    });

    await expect(uploadDriveItem(input(new AbortController().signal))).rejects.toBeInstanceOf(ApiError);

    expect(FakeXhr.instances).toHaveLength(0);
    expect(sentPaths(calls)).toEqual(['POST /api/v1/drive/uploads']);
  });

  it('sends no create when the signal aborts during the PUT', async () => {
    const calls = stubApi({
      'POST /api/v1/drive/uploads': { status: 201, body: GRANT },
      'POST /api/v1/drive/items': { status: 201, body: ITEM },
    });
    const controller = new AbortController();
    const done = uploadDriveItem(input(controller.signal));
    const failed = expect(done).rejects.toMatchObject({ name: 'UploadFailedError', status: null });

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    controller.abort();

    await failed;
    expect(sentPaths(calls)).toEqual(['POST /api/v1/drive/uploads']);
  });

  it('sends no create when the signal aborts after the PUT resolved', async () => {
    const calls = stubApi({
      'POST /api/v1/drive/uploads': { status: 201, body: GRANT },
      'POST /api/v1/drive/items': { status: 201, body: ITEM },
    });
    const controller = new AbortController();
    const done = uploadDriveItem(input(controller.signal));
    const failed = expect(done).rejects.toMatchObject({ name: 'UploadFailedError', status: null });

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    FakeXhr.instances[0]!.respond(200);
    controller.abort();

    await failed;
    expect(sentPaths(calls)).toEqual(['POST /api/v1/drive/uploads']);
  });
});
