import type {
  RunResponse,
  StartRunRequest,
  UploadRequest,
  UploadResponse,
} from '@flow-cortex/api-client';

export interface StartWithUploadDeps {
  requestUpload: (body: UploadRequest) => Promise<UploadResponse>;
  uploadFile: (
    url: string,
    file: File,
    headers: Record<string, string>,
    onProgress: (percent: number) => void,
  ) => Promise<void>;
  startRun: (body: StartRunRequest) => Promise<RunResponse>;
}

export type StartPhase = { kind: 'uploading'; percent: number } | { kind: 'starting' };

/** Which request of the sequence failed, so the form can pick the sentence; `cause` is its error. */
export type StartStage = 'request' | 'upload' | 'start';

export class StartStageError extends Error {
  readonly stage: StartStage;
  override readonly cause: unknown;
  /** The run start that was refused (stage `start` only), so it can be resent without re-uploading. */
  readonly request: StartRunRequest | undefined;

  constructor(stage: StartStage, cause: unknown, request?: StartRunRequest) {
    super(`Start failed at stage ${stage}`);
    this.name = 'StartStageError';
    this.stage = stage;
    this.cause = cause;
    this.request = request;
  }
}

async function stage<T>(name: StartStage, work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch (error) {
    throw new StartStageError(name, error);
  }
}

/**
 * One press of Start: `POST /uploads`, the PUT, then `POST …/runs` only once the PUT succeeded,
 * so no run is ever started with an upload id whose object is missing. A call made while one is
 * in flight sends nothing and resolves `null`; a call after a failure starts again from `/uploads`.
 * `resend` repeats only the run start of a refused press, sharing the same in-flight guard.
 */
export function createStartWithUpload(deps: StartWithUploadDeps) {
  let inFlight = false;

  async function startRun(request: StartRunRequest): Promise<string> {
    let run: RunResponse;
    try {
      run = await deps.startRun(request);
    } catch (error) {
      throw new StartStageError('start', error, request);
    }
    if (!run.id) {
      throw new StartStageError('start', new Error('The run start answered without a run id'));
    }
    return run.id;
  }

  async function resend(
    request: StartRunRequest,
    onPhase: (phase: StartPhase) => void,
  ): Promise<string | null> {
    if (inFlight) {
      return null;
    }
    inFlight = true;
    try {
      onPhase({ kind: 'starting' });
      return await startRun(request);
    } finally {
      inFlight = false;
    }
  }

  async function start(file: File, onPhase: (phase: StartPhase) => void): Promise<string | null> {
    if (inFlight) {
      return null;
    }
    inFlight = true;
    try {
      onPhase({ kind: 'uploading', percent: 0 });
      const grant = await stage('request', () =>
        deps.requestUpload({ fileName: file.name, size: file.size }),
      );
      await stage('upload', () =>
        deps.uploadFile(grant.url, file, grant.headers, (percent) =>
          onPhase({ kind: 'uploading', percent }),
        ),
      );
      onPhase({ kind: 'starting' });
      return await startRun({ uploadId: grant.uploadId, originalName: file.name });
    } finally {
      inFlight = false;
    }
  }

  return Object.assign(start, { resend });
}
