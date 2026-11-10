import {
  ApiError,
  authHeaders,
  getOnboardingStatus,
  patchOnboardingQuiz,
  saveOnboardingProgress,
  unwrap,
  useAuthToken,
  type OnboardingProgressBody,
  type OnboardingQuizBody,
  type OnboardingStatusResponse,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { useFeatures } from '../../app/useFeatures';
import type {
  AiLevel,
  ContentType,
  Industry,
  Purpose,
  QuizQuestion,
  Source,
  UsageType,
} from './onboardingVocabulary';

export interface Answers {
  purposes: readonly Purpose[] | null;
  source: Source | null;
  aiLevel: AiLevel | null;
  industries: readonly Industry[] | null;
  contentTypes: readonly ContentType[] | null;
  usageType: UsageType | null;
}

/** The request for one question: an empty pick is sent as `null`, the last question also finishes. */
export function quizBody(question: QuizQuestion, answers: Answers): OnboardingQuizBody {
  const list = <T extends string>(values: readonly T[] | null) =>
    values && values.length > 0 ? [...values] : null;
  switch (question) {
    case 1:
      return { purposes: list(answers.purposes) };
    case 2:
      return { source: answers.source };
    case 3:
      return { aiLevel: answers.aiLevel };
    case 4:
      return { industries: list(answers.industries) };
    case 5:
      return { contentTypes: list(answers.contentTypes) };
    case 6:
      return { usageType: answers.usageType, finished: true };
  }
}

export const ONBOARDING_STATUS_KEY = ['onboarding', 'status'] as const;

/** `closed`: another tab finished the journey (409), so this one stops without an error. */
export type ProgressOutcome = 'saved' | 'closed' | 'failed';

const ANSWER_FIELDS = [
  'purposes',
  'source',
  'aiLevel',
  'industries',
  'contentTypes',
  'usageType',
] as const;

// `unwrap` throws for every status >= 400, so what it returns is the success body.
async function fetchStatus(): Promise<OnboardingStatusResponse> {
  return (await unwrap(getOnboardingStatus(authHeaders()))) as OnboardingStatusResponse;
}

/** The status query; it sends nothing while the feature is off or nobody is signed in. */
export function useOnboardingStatus() {
  const { onboarding } = useFeatures();
  const member = useAuthToken() !== null;
  return useQuery({
    queryKey: ONBOARDING_STATUS_KEY,
    queryFn: fetchStatus,
    enabled: onboarding && member,
  });
}

export function useOnboardingActions() {
  const queryClient = useQueryClient();

  const progress = useMutation({
    mutationFn: (body: OnboardingProgressBody) =>
      unwrap(saveOnboardingProgress(body, authHeaders())),
  });
  const quiz = useMutation({
    mutationFn: (body: OnboardingQuizBody) => unwrap(patchOnboardingQuiz(body, authHeaders())),
    onSuccess: (_saved, body) => {
      queryClient.setQueryData<OnboardingStatusResponse>(ONBOARDING_STATUS_KEY, (status) => {
        if (!status) return status;
        const next = { ...status };
        for (const field of ANSWER_FIELDS) {
          if (body[field] !== undefined) Object.assign(next, { [field]: body[field] });
        }
        return next;
      });
    },
  });

  const { mutateAsync: sendProgress } = progress;
  const { mutateAsync: sendQuiz } = quiz;

  const saveProgress = useCallback(
    async (body: OnboardingProgressBody): Promise<ProgressOutcome> => {
      try {
        await sendProgress(body);
        return 'saved';
      } catch (error) {
        return error instanceof ApiError && error.status === 409 ? 'closed' : 'failed';
      }
    },
    [sendProgress],
  );

  /** Resolves when saved; rejects with the request's error so the dialog can show a retry. */
  const saveQuiz = useCallback((body: OnboardingQuizBody) => sendQuiz(body), [sendQuiz]);

  const refreshStatus = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ONBOARDING_STATUS_KEY }),
    [queryClient],
  );

  return { saveProgress, saveQuiz, refreshStatus, savingQuiz: quiz.isPending };
}
