import { ApiError, type UploadResponse } from '@flow-cortex/api-client';
import { createStartWithUpload, StartStageError, type StartWithUploadDeps } from './startWithUpload';
import { UploadFailedError } from './uploadFile';

const GRANT: UploadResponse = {
  uploadId: 'up-1',
  url: 'http://localhost:9000/flowcortex-uploads/u/up-1',
  method: 'PUT',
  headers: { 'Content-Type': 'video/mp4' },
  expiresAt: '2026-10-01T10:15:00Z',
  maxBytes: 524288000,
};

function clip() {
  return new File(['0123456789'], 'holiday clip.mp4', { type: 'video/mp4' });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fakeDeps(overrides: Partial<StartWithUploadDeps> = {}) {
  const calls: string[] = [];
  const deps: StartWithUploadDeps = {
    requestUpload: vi.fn(async (body) => {
      calls.push(`POST /uploads ${body.fileName} ${body.size}`);
      return GRANT;
    }),
    uploadFile: vi.fn(async (url, _file, headers, onProgress) => {
      calls.push(`PUT ${url} ${JSON.stringify(headers)}`);
      onProgress(100);
    }),
    startRun: vi.fn(async (body) => {
      calls.push(`POST /runs ${JSON.stringify(body)}`);
      return { id: 'run-1' };
    }),
    ...overrides,
  };
  return { deps, calls };
}

describe('startWithUpload', () => {
  it('requests an upload, PUTs the file, then starts the run and returns its id', async () => {
    const { deps, calls } = fakeDeps();
    const phases: unknown[] = [];

    const runId = await createStartWithUpload(deps)(clip(), (phase) => phases.push(phase));

    expect(runId).toBe('run-1');
    expect(calls).toEqual([
      'POST /uploads holiday clip.mp4 10',
      `PUT ${GRANT.url} {"Content-Type":"video/mp4"}`,
      'POST /runs {"uploadId":"up-1","originalName":"holiday clip.mp4"}',
    ]);
    expect(phases).toEqual([
      { kind: 'uploading', percent: 0 },
      { kind: 'uploading', percent: 100 },
      { kind: 'starting' },
    ]);
  });

  it('starts the run only after the PUT resolved', async () => {
    const put = deferred<void>();
    const { deps, calls } = fakeDeps({ uploadFile: vi.fn(() => put.promise) });

    const started = createStartWithUpload(deps)(clip(), () => {});
    await vi.waitFor(() => expect(deps.uploadFile).toHaveBeenCalledTimes(1));
    expect(deps.startRun).not.toHaveBeenCalled();

    put.resolve();
    await started;
    expect(calls.at(-1)).toMatch(/^POST \/runs/);
  });

  it('ignores a second call while one is in flight and sends nothing for it', async () => {
    const put = deferred<void>();
    const { deps } = fakeDeps({ uploadFile: vi.fn(() => put.promise) });
    const start = createStartWithUpload(deps);

    const first = start(clip(), () => {});
    const second = await start(clip(), () => {});
    put.resolve();
    await first;

    expect(second).toBeNull();
    expect(deps.requestUpload).toHaveBeenCalledTimes(1);
    expect(deps.uploadFile).toHaveBeenCalledTimes(1);
    expect(deps.startRun).toHaveBeenCalledTimes(1);
  });

  it.each([400, 503])('rejects with stage "request" when /uploads answers %i', async (status) => {
    const refusal = new ApiError(status, { status, detail: 'Media engine is currently disabled' });
    const { deps } = fakeDeps({ requestUpload: vi.fn().mockRejectedValue(refusal) });

    const failure = createStartWithUpload(deps)(clip(), () => {});

    await expect(failure).rejects.toEqual(new StartStageError('request', refusal));
    expect(deps.uploadFile).not.toHaveBeenCalled();
    expect(deps.startRun).not.toHaveBeenCalled();
  });

  it('rejects with stage "upload" when the PUT fails and never starts the run', async () => {
    const { deps } = fakeDeps({ uploadFile: vi.fn().mockRejectedValue(new UploadFailedError(403)) });

    const failure = createStartWithUpload(deps)(clip(), () => {});

    await expect(failure).rejects.toMatchObject({ stage: 'upload' });
    expect(deps.startRun).not.toHaveBeenCalled();
  });

  it('rejects with stage "start" when the run start answers 400', async () => {
    const refusal = new ApiError(400, { status: 400, errors: { uploadId: 'Upload the file again' } });
    const { deps } = fakeDeps({ startRun: vi.fn().mockRejectedValue(refusal) });

    await expect(createStartWithUpload(deps)(clip(), () => {})).rejects.toEqual(
      new StartStageError('start', refusal),
    );
  });

  it('starts again from /uploads on a retry after a failure', async () => {
    const uploadFile = vi
      .fn<Parameters<StartWithUploadDeps["uploadFile"]>, ReturnType<StartWithUploadDeps["uploadFile"]>>()
      .mockRejectedValueOnce(new UploadFailedError(null))
      .mockResolvedValueOnce(undefined);
    const { deps } = fakeDeps({ uploadFile });
    const start = createStartWithUpload(deps);

    await expect(start(clip(), () => {})).rejects.toMatchObject({ stage: 'upload' });
    await expect(start(clip(), () => {})).resolves.toBe('run-1');

    expect(deps.requestUpload).toHaveBeenCalledTimes(2);
    expect(uploadFile).toHaveBeenCalledTimes(2);
    expect(deps.startRun).toHaveBeenCalledTimes(1);
  });
});
