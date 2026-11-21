import {
  isQuizQuestion,
  QUESTION_COUNT,
  type ProgressStep,
  type QuizQuestion,
} from './onboardingVocabulary';

export type OnboardingMode = 'first' | 'replay';

export interface FlowContext {
  mode: OnboardingMode;
  /** Viewport below the desktop breakpoint: the sidebar is a drawer, so there is no tour. */
  narrow: boolean;
  /** The sidebar navigation is rendered and can be pointed at. */
  tourAvailable: boolean;
  /** Replay only: one of the core answers is missing, so the survey is offered again. */
  replaySurvey: boolean;
}

export type FlowState =
  | { screen: 'closed' }
  | { screen: 'welcome' }
  | { screen: 'tour' }
  | { screen: 'survey-intro' }
  | { screen: 'quiz'; question: QuizQuestion; error: boolean }
  | { screen: 'done'; surveyDeferred: boolean };

export type FlowEvent =
  | { type: 'start' }
  | { type: 'next' }
  | { type: 'back' }
  | { type: 'close' }
  | { type: 'skip' }
  | { type: 'narrow' }
  | { type: 'survey-now' }
  | { type: 'survey-later' }
  | { type: 'quiz-saved' }
  | { type: 'quiz-save-failed' }
  | {
      type: 'resume';
      step: string;
      question?: number | null | undefined;
      /** The survey was deferred and a core answer is still missing. */
      surveyDeferred: boolean;
    };

export type JourneyStep = 'interface' | 'survey';

export interface StoredProgress {
  step: ProgressStep;
  question?: QuizQuestion;
}

export const CLOSED: FlowState = { screen: 'closed' };

const WELCOME: FlowState = { screen: 'welcome' };
const TOUR: FlowState = { screen: 'tour' };
const SURVEY_INTRO: FlowState = { screen: 'survey-intro' };

const hasTour = (ctx: FlowContext) => ctx.tourAvailable && !ctx.narrow;
const hasSurvey = (ctx: FlowContext) => ctx.mode === 'first' || ctx.replaySurvey;
const done = (surveyDeferred: boolean): FlowState => ({ screen: 'done', surveyDeferred });
const quiz = (question: QuizQuestion, error = false): FlowState => ({
  screen: 'quiz',
  question,
  error,
});

const afterIntro = (ctx: FlowContext): FlowState => (hasSurvey(ctx) ? SURVEY_INTRO : done(false));
const beforeSurvey = (ctx: FlowContext): FlowState => (hasTour(ctx) ? TOUR : WELCOME);

function resumeAt(
  event: Extract<FlowEvent, { type: 'resume' }>,
  ctx: FlowContext,
): FlowState {
  const { step, question } = event;
  switch (step) {
    case 'tour:home':
      return hasTour(ctx) ? TOUR : SURVEY_INTRO;
    case 'survey-intro':
      return SURVEY_INTRO;
    case 'quiz':
      return quiz(isQuizQuestion(question) ? question : 1);
    case 'done':
      return done(event.surveyDeferred);
    default:
      return WELCOME;
  }
}

export function onboardingFlowReducer(
  state: FlowState,
  event: FlowEvent,
  ctx: FlowContext,
): FlowState {
  if (state.screen === 'closed') {
    if (event.type === 'start') return WELCOME;
    if (event.type === 'resume' && ctx.mode === 'first') {
      return resumeAt(event, ctx);
    }
    return state;
  }
  if (event.type === 'close') return CLOSED;

  switch (state.screen) {
    case 'welcome':
      if (event.type === 'start' || event.type === 'next') {
        return hasTour(ctx) ? TOUR : afterIntro(ctx);
      }
      return event.type === 'skip' ? CLOSED : state;
    case 'tour':
      switch (event.type) {
        case 'next':
          return afterIntro(ctx);
        case 'back':
          return WELCOME;
        case 'skip':
          return CLOSED;
        case 'narrow':
          return ctx.mode === 'first' ? SURVEY_INTRO : CLOSED;
        default:
          return state;
      }
    case 'survey-intro':
      switch (event.type) {
        case 'survey-now':
          return quiz(1);
        case 'survey-later':
          return done(true);
        case 'back':
          return beforeSurvey(ctx);
        default:
          return state;
      }
    case 'quiz':
      switch (event.type) {
        case 'quiz-saved':
          return state.question < QUESTION_COUNT
            ? quiz((state.question + 1) as QuizQuestion)
            : done(false);
        case 'quiz-save-failed':
          return quiz(state.question, true);
        case 'back':
          return state.question > 1 ? quiz((state.question - 1) as QuizQuestion) : SURVEY_INTRO;
        case 'skip':
          return CLOSED;
        default:
          return state;
      }
    case 'done':
      switch (event.type) {
        case 'next':
          return CLOSED;
        case 'back':
          if (!hasSurvey(ctx)) return beforeSurvey(ctx);
          return state.surveyDeferred ? SURVEY_INTRO : quiz(QUESTION_COUNT);
        default:
          return state;
      }
  }
}

/** The step to store for a screen; null for a screen that is not stored. */
export function progressOf(state: FlowState): StoredProgress | null {
  switch (state.screen) {
    case 'welcome':
      return { step: 'welcome' };
    case 'tour':
      return { step: 'tour:home' };
    case 'survey-intro':
      return { step: 'survey-intro' };
    case 'quiz':
      return { step: 'quiz', question: state.question };
    case 'done':
      return { step: 'done' };
    case 'closed':
      return null;
  }
}

export function journeySteps(ctx: FlowContext): JourneyStep[] {
  return [
    ...(hasTour(ctx) ? (['interface'] as const) : []),
    ...(hasSurvey(ctx) ? (['survey'] as const) : []),
  ];
}

/** The steps Done lists as completed: the survey drops out when it was deferred. */
export function doneSteps(ctx: FlowContext, surveyDeferred: boolean): JourneyStep[] {
  return journeySteps(ctx).filter((step) => !(step === 'survey' && surveyDeferred));
}
