import type { OnboardingProgressBody, OnboardingStatusResponse } from '@flow-cortex/api-client';
import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useAccountLeft } from '../../app/AccountCacheReset';
import { useIsDesktop } from '../../app/sidebarMode';
import { useFeatures } from '../../app/useFeatures';
import { DoneDialog } from './DoneDialog';
import {
  CLOSED,
  doneSteps,
  onboardingFlowReducer,
  progressOf,
  type FlowContext,
  type FlowEvent,
  type FlowState,
  type OnboardingMode,
} from './onboardingFlow';
import {
  consumeReplayRequest,
  markExited,
  resetOnboardingSession,
  useOnboardingSession,
} from './onboardingSession';
import { QuizDialog } from './QuizDialog';
import { SurveyIntroDialog } from './SurveyIntroDialog';
import { SIDEBAR_NAV_SELECTOR, TourTip } from './TourTip';
import { quizBody, useOnboardingActions, useOnboardingStatus, type Answers } from './useOnboarding';
import { WelcomeDialog } from './WelcomeDialog';

const USER_MENU_TRIGGER_SELECTOR = '.cortex-user-menu__trigger';

function answersOf(status: OnboardingStatusResponse | undefined): Answers {
  return {
    purposes: status?.purposes ?? null,
    source: status?.source ?? null,
    aiLevel: status?.aiLevel ?? null,
    industries: status?.industries ?? null,
    contentTypes: status?.contentTypes ?? null,
    usageType: status?.usageType ?? null,
  };
}

/** A replay asks the survey again only when one of the four core answers is missing. */
function hasMissingCoreAnswer(answers: Answers): boolean {
  return (
    answers.usageType === null ||
    answers.aiLevel === null ||
    !answers.industries?.length ||
    !answers.contentTypes?.length
  );
}

function progressBody(state: FlowState, surveyDeferred: boolean): OnboardingProgressBody | null {
  const stored = progressOf(state);
  if (!stored) return null;
  return {
    step: stored.step,
    ...(stored.question === undefined ? {} : { question: stored.question }),
    ...(surveyDeferred ? { surveyDeferred: true } : {}),
  };
}

function sameProgress(a: FlowState, b: FlowState): boolean {
  const left = progressOf(a);
  const right = progressOf(b);
  return left?.step === right?.step && left?.question === right?.question;
}

/**
 * Owns the journey: opens it from the status, feeds events into the pure reducer and performs
 * the side effects (progress writes in first-time mode, quiz saves, focus return).
 */
export function OnboardingFlow() {
  const { pathname } = useLocation();
  const isDesktop = useIsDesktop();
  const status = useOnboardingStatus();
  const session = useOnboardingSession();
  const actions = useOnboardingActions();
  const [flow, setFlow] = useState<FlowState>(CLOSED);

  const flowRef = useRef<FlowState>(CLOSED);
  const modeRef = useRef<OnboardingMode>('first');
  const openerRef = useRef<HTMLElement | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const generationRef = useRef(0);
  const writesRef = useRef<Promise<unknown>>(Promise.resolve());

  const answers = answersOf(status.data);
  const replaySurvey = hasMissingCoreAnswer(answers);

  const context = (): FlowContext => ({
    mode: modeRef.current,
    narrow: !isDesktop,
    tourAvailable: document.querySelector(SIDEBAR_NAV_SELECTOR) !== null,
    replaySurvey,
  });

  const show = (next: FlowState) => {
    flowRef.current = next;
    setFlow(next);
  };

  const leave = () => {
    generationRef.current += 1;
    show(CLOSED);
    if (modeRef.current === 'first') markExited();
    const opener =
      modeRef.current === 'replay'
        ? document.querySelector<HTMLElement>(USER_MENU_TRIGGER_SELECTOR)
        : openerRef.current;
    openerRef.current = null;
    returnFocusRef.current = opener;
  };

  // Focus goes back only after the dialog has unmounted: closing a native dialog restores focus
  // itself and would otherwise overwrite it.
  useEffect(() => {
    if (flow.screen !== 'closed') return;
    const target = returnFocusRef.current;
    returnFocusRef.current = null;
    if (target?.isConnected) target.focus();
  }, [flow]);

  // Writes go out one at a time so a stored step never overtakes a later one.
  const write = (body: OnboardingProgressBody) => {
    const generation = generationRef.current;
    const run = writesRef.current.then(() => actions.saveProgress(body));
    writesRef.current = run;
    return run.then((outcome) => {
      if (outcome === 'closed' && generation === generationRef.current) leave();
      return outcome;
    });
  };

  const dispatch = (event: FlowEvent) => {
    const previous = flowRef.current;
    const ctx = context();
    const next = onboardingFlowReducer(previous, event, ctx);
    if (next === previous) return;
    const wasClosed = previous.screen === 'closed';
    const leftByChoice = event.type === 'skip' || event.type === 'next';

    if (next.screen === 'closed') {
      if (ctx.mode === 'first' && leftByChoice) {
        const deferred = previous.screen === 'done' && previous.surveyDeferred;
        const body: OnboardingProgressBody =
          event.type === 'skip'
            ? { finished: true }
            : { step: 'done', finished: true, ...(deferred ? { surveyDeferred: true } : {}) };
        void write(body).then(() => actions.refreshStatus());
      }
      leave();
      return;
    }

    if (wasClosed) {
      openerRef.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
    }
    show(next);
    if (ctx.mode === 'first' && !wasClosed && !sameProgress(previous, next)) {
      const body = progressBody(next, next.screen === 'done' && next.surveyDeferred);
      if (body) void write(body);
    }
  };

  const dispatchRef = useRef(dispatch);
  dispatchRef.current = dispatch;

  useAccountLeft(() => {
    resetOnboardingSession();
    generationRef.current += 1;
    show(CLOSED);
  });

  const entry = status.data?.entry;
  const progress = status.data?.progress;
  const onboarding = useFeatures().onboarding;

  useEffect(() => {
    if (!onboarding || flowRef.current.screen !== 'closed') return;
    if (session.replayRequested) {
      consumeReplayRequest();
      if (entry === null) {
        modeRef.current = 'replay';
        dispatchRef.current({ type: 'start' });
      }
      return;
    }
    if (pathname !== '/' || entry !== 'first' || session.exitedThisSession) return;
    modeRef.current = 'first';
    dispatchRef.current({
      type: 'resume',
      step: progress?.step ?? '',
      question: progress?.question,
    });
  }, [onboarding, pathname, entry, progress, session.exitedThisSession, session.replayRequested]);

  useEffect(() => {
    if (!isDesktop && flowRef.current.screen === 'tour') dispatchRef.current({ type: 'narrow' });
  }, [isDesktop]);

  const saveAnswer = async (picked: Answers) => {
    const current = flowRef.current;
    if (current.screen !== 'quiz') return;
    const generation = generationRef.current;
    try {
      await actions.saveQuiz(quizBody(current.question, picked));
    } catch {
      if (generation === generationRef.current && flowRef.current === current) {
        dispatch({ type: 'quiz-save-failed' });
      }
      return;
    }
    if (generation === generationRef.current && flowRef.current === current) {
      dispatch({ type: 'quiz-saved' });
    }
  };

  const send = (type: Exclude<FlowEvent, { type: 'resume' }>['type']) =>
    dispatch({ type } as Exclude<FlowEvent, { type: 'resume' }>);

  if (!onboarding) return null;

  switch (flow.screen) {
    case 'closed':
      return null;
    case 'welcome':
      return (
        <WelcomeDialog
          onStart={() => send('start')}
          onSkip={() => send('skip')}
          onClose={() => send('close')}
        />
      );
    case 'tour':
      return (
        <TourTip
          onNext={() => send('next')}
          onBack={() => send('back')}
          onSkip={() => send('skip')}
          onClose={() => send('close')}
        />
      );
    case 'survey-intro':
      return (
        <SurveyIntroDialog
          onStart={() => send('survey-now')}
          onLater={() => send('survey-later')}
          onBack={() => send('back')}
          onClose={() => send('close')}
        />
      );
    case 'quiz':
      return (
        <QuizDialog
          key={flow.question}
          question={flow.question}
          answers={answers}
          saving={actions.savingQuiz}
          error={flow.error}
          onNext={(picked) => void saveAnswer(picked)}
          onBack={() => send('back')}
          onSkip={() => send('skip')}
          onClose={() => send('close')}
        />
      );
    case 'done':
      return (
        <DoneDialog
          steps={doneSteps(context(), flow.surveyDeferred)}
          onFinish={() => send('next')}
          onBack={() => send('back')}
          onClose={() => send('close')}
        />
      );
  }
}
