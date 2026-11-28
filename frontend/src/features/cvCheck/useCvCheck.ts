import { useMutation } from '@tanstack/react-query';
import { sessionFetch } from '@flow-cortex/api-client';
import type {
  CvCheckReport,
  CvJobProfile,
  JobRecommendationsResponse,
} from './cvCheckTypes';

export interface CheckCvParams {
  readonly cvFile?: File | null;
  readonly cvText?: string;
  readonly jobDescription?: string;
  readonly lang?: string;
}

export interface RecommendJobsParams extends CvJobProfile {
  readonly lang?: string;
}

export class CvCheckError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly code?: string,
  ) {
    super(message);
    this.name = 'CvCheckError';
  }
}

function buildCvCheckRequest(params: CheckCvParams): { body: BodyInit; headers: HeadersInit } {
  if (params.cvFile) {
    const formData = new FormData();
    formData.append('cvFile', params.cvFile, params.cvFile.name);
    if (params.jobDescription) formData.append('jobDescription', params.jobDescription);
    if (params.cvText) formData.append('cvText', params.cvText);
    if (params.lang) formData.append('lang', params.lang);
    return { body: formData, headers: {} };
  }

  return {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      cvText: params.cvText ?? '',
      ...(params.jobDescription ? { jobDescription: params.jobDescription } : {}),
      ...(params.lang ? { lang: params.lang } : {}),
    }),
  };
}

async function readErrorDetail(response: Response): Promise<string> {
  try {
    const data = (await response.json()) as { detail?: string; message?: string; title?: string };
    return data.detail ?? data.message ?? data.title ?? '';
  } catch {
    return '';
  }
}

const STATUS_ERROR_CODES: Readonly<Record<number, string>> = {
  400: 'cv-check-bad-request',
  413: 'cv-file-too-large',
  429: 'cv-check-busy',
  502: 'cv-check-unavailable',
};

function createCvCheckError(status: number, errorDetail: string): CvCheckError {
  if (status === 422) {
    if (errorDetail.includes('cv-check-blocked')) {
      return new CvCheckError(errorDetail, 422, 'cv-check-blocked');
    }
    const idx = errorDetail.indexOf('cv-not-a-cv');
    const reason =
      idx >= 0 ? errorDetail.slice(idx + 'cv-not-a-cv'.length).replace(/^:\s*/, '').trim() : '';
    return new CvCheckError(reason, 422, 'cv-not-a-cv');
  }

  const mappedCode = STATUS_ERROR_CODES[status];
  if (mappedCode) {
    return new CvCheckError(errorDetail || mappedCode, status, mappedCode);
  }
  return new CvCheckError(errorDetail || 'cv-check-failed', status);
}

export async function executeCvCheck(params: CheckCvParams): Promise<CvCheckReport> {
  const { body, headers } = buildCvCheckRequest(params);
  const response = await sessionFetch('/api/v1/cv-check', {
    method: 'POST',
    headers,
    body,
  });

  if (!response.ok) {
    const errorDetail = await readErrorDetail(response);
    throw createCvCheckError(response.status, errorDetail);
  }

  return (await response.json()) as CvCheckReport;
}

export function useCvCheckMutation() {
  return useMutation({
    mutationFn: executeCvCheck,
  });
}

export async function executeRecommendJobs(
  profile: RecommendJobsParams,
): Promise<JobRecommendationsResponse> {
  const response = await sessionFetch('/api/v1/cv-check/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  });

  if (!response.ok) {
    const errorDetail = await readErrorDetail(response);
    throw new CvCheckError(errorDetail || 'cv-jobs-failed', response.status);
  }

  return (await response.json()) as JobRecommendationsResponse;
}

export function useRecommendJobsMutation() {
  return useMutation({
    mutationFn: executeRecommendJobs,
  });
}
