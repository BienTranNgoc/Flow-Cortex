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

export async function executeCvCheck(params: CheckCvParams): Promise<CvCheckReport> {
  let body: BodyInit;
  let headers: HeadersInit = {};

  if (params.cvFile) {
    const formData = new FormData();
    formData.append('cvFile', params.cvFile, params.cvFile.name);
    if (params.jobDescription) {
      formData.append('jobDescription', params.jobDescription);
    }
    if (params.cvText) {
      formData.append('cvText', params.cvText);
    }
    body = formData;
  } else {
    headers = {
      'Content-Type': 'application/json',
    };
    body = JSON.stringify({
      cvText: params.cvText ?? '',
      ...(params.jobDescription ? { jobDescription: params.jobDescription } : {}),
    });
  }

  const response = await sessionFetch('/api/v1/cv-check', {
    method: 'POST',
    headers,
    body,
  });

  if (!response.ok) {
    let errorDetail = '';
    try {
      const body = (await response.json()) as { detail?: string; title?: string };
      errorDetail = body.detail ?? body.title ?? '';
    } catch {
      // ignore
    }

    if (response.status === 422) {
      throw new CvCheckError(errorDetail || 'cv-check-blocked', 422, 'cv-check-blocked');
    }
    if (response.status === 429) {
      throw new CvCheckError(errorDetail || 'cv-check-busy', 429, 'cv-check-busy');
    }
    if (response.status === 502) {
      throw new CvCheckError(errorDetail || 'cv-check-unavailable', 502, 'cv-check-unavailable');
    }
    if (response.status === 400) {
      throw new CvCheckError(errorDetail || 'cv-check-bad-request', 400, 'cv-check-bad-request');
    }
    throw new CvCheckError(errorDetail || 'cv-check-failed', response.status);
  }

  return (await response.json()) as CvCheckReport;
}

export function useCvCheckMutation() {
  return useMutation({
    mutationFn: executeCvCheck,
  });
}

export async function executeRecommendJobs(
  profile: CvJobProfile,
): Promise<JobRecommendationsResponse> {
  const response = await sessionFetch('/api/v1/cv-check/jobs', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(profile),
  });

  if (!response.ok) {
    let errorDetail = '';
    try {
      const errBody = (await response.json()) as { detail?: string; title?: string };
      errorDetail = errBody.detail ?? errBody.title ?? '';
    } catch {
      // ignore
    }
    throw new CvCheckError(errorDetail || 'cv-jobs-failed', response.status);
  }

  return (await response.json()) as JobRecommendationsResponse;
}

export function useRecommendJobsMutation() {
  return useMutation({
    mutationFn: executeRecommendJobs,
  });
}
