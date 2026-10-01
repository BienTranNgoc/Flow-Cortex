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

  constructor(stage: StartStage, cause: unknown) {
    super(`Start failed at stage ${stage}`);
    this.name = 'StartStageError';
    this.stage = stage;
    this.cause = cause;
  }
}

/**
 * One press of Start: `POST /uploads`, the PUT, then `POST …/runs` only once the PUT succeeded,
 * so no run is ever started with an upload id whose object is missing. A call made while one is
 * in flight sends nothing and resolves `null`; a call after a failure starts again from `/uploads`.
 */
export function createStartWithUpload(deps: StartWithUploadDeps) {
  let inFlight = false;

  async function stage<T>(name: StartStage, work: () => Promise<T>): Promise<T> {
    try {
      return await work();
    } catch (error) {
      throw new StartStageError(name, error);
    }
  }

  return async function start(
    file: File,
    onPhase: (phase: StartPhase) => void,
  ): Promise<string | null> {
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
      const run = await stage('start', () =>
        deps.startRun({ uploadId: grant.uploadId, originalName: file.name }),
      );
      if (!run.id) {
        throw new StartStageError('start', new Error('The run start answered without a run id'));
      }
      return run.id;
    } finally {
      inFlight = false;
    }
  };
}
