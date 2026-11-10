import {
  CLOSED,
  doneSteps,
  journeySteps,
  onboardingFlowReducer,
  progressOf,
  type FlowContext,
  type FlowEvent,
  type FlowState,
} from './onboardingFlow';

const grid = (['first', 'replay'] as const).flatMap((mode) =>
  ([true, false] as const).flatMap((narrow) =>
    ([true, false] as const).map((tourAvailable) => ({
      label: `${mode} / ${narrow ? 'narrow' : 'wide'} / ${tourAvailable ? 'tour' : 'no tour'}`,
      ctx: { mode, narrow, tourAvailable, replaySurvey: true } as FlowContext,
    })),
  ),
);

const hasTour = (ctx: FlowContext) => ctx.tourAvailable && !ctx.narrow;
const reduce = (state: FlowState, event: FlowEvent, ctx: FlowContext) =>
  onboardingFlowReducer(state, event, ctx);
const quiz = (question: 1 | 2 | 3 | 4 | 5 | 6, error = false): FlowState => ({
  screen: 'quiz',
  question,
  error,
});
const WELCOME: FlowState = { screen: 'welcome' };
const TOUR: FlowState = { screen: 'tour' };
const SURVEY: FlowState = { screen: 'survey-intro' };
const DONE: FlowState = { screen: 'done', surveyDeferred: false };
const DONE_DEFERRED: FlowState = { screen: 'done', surveyDeferred: true };

describe.each(grid)('journey ($label)', ({ ctx }) => {
  it('welcome start goes to the tour when there is one, else the survey intro', () => {
    const next = reduce(WELCOME, { type: 'start' }, ctx);
    expect(next.screen).toBe(hasTour(ctx) ? 'tour' : 'survey-intro');
  });

  it('tour next goes to the survey intro and back returns to welcome', () => {
    expect(reduce(TOUR, { type: 'next' }, ctx)).toEqual(SURVEY);
    expect(reduce(TOUR, { type: 'back' }, ctx)).toEqual(WELCOME);
  });

  it('survey-now opens question 1 and survey-later goes to done deferred', () => {
    expect(reduce(SURVEY, { type: 'survey-now' }, ctx)).toEqual(quiz(1));
    expect(reduce(SURVEY, { type: 'survey-later' }, ctx)).toEqual(DONE_DEFERRED);
  });

  it('survey intro back returns to the tour when there is one, else welcome', () => {
    const next = reduce(SURVEY, { type: 'back' }, ctx);
    expect(next.screen).toBe(hasTour(ctx) ? 'tour' : 'welcome');
  });

  it('quiz-saved advances 1 to 6 and then reaches done', () => {
    let state: FlowState = quiz(1);
    for (const question of [2, 3, 4, 5, 6] as const) {
      state = reduce(state, { type: 'quiz-saved' }, ctx);
      expect(state).toEqual(quiz(question));
    }
    expect(reduce(state, { type: 'quiz-saved' }, ctx)).toEqual(DONE);
  });

  it('quiz-save-failed sets the error and quiz-saved clears it', () => {
    const failed = reduce(quiz(3), { type: 'quiz-save-failed' }, ctx);
    expect(failed).toEqual(quiz(3, true));
    expect(reduce(failed, { type: 'quiz-saved' }, ctx)).toEqual(quiz(4));
  });

  it('back from question 1 goes to the survey intro and from 3 to 2', () => {
    expect(reduce(quiz(1), { type: 'back' }, ctx)).toEqual(SURVEY);
    expect(reduce(quiz(3, true), { type: 'back' }, ctx)).toEqual(quiz(2));
  });

  it.each([WELCOME, TOUR, SURVEY, quiz(2), DONE])('close from $screen closes', (state) => {
    expect(reduce(state, { type: 'close' }, ctx)).toEqual(CLOSED);
  });

  it.each([WELCOME, TOUR, quiz(5)])('skip from $screen closes', (state) => {
    expect(reduce(state, { type: 'skip' }, ctx)).toEqual(CLOSED);
  });

  it('skip does nothing on the survey intro and done', () => {
    expect(reduce(SURVEY, { type: 'skip' }, ctx)).toEqual(SURVEY);
    expect(reduce(DONE, { type: 'skip' }, ctx)).toEqual(DONE);
  });

  it('a narrow event on the tour leaves it, for the survey (first) or closed (replay)', () => {
    const next = reduce(TOUR, { type: 'narrow' }, ctx);
    expect(next).toEqual(ctx.mode === 'first' ? SURVEY : CLOSED);
  });

  it('a narrow event elsewhere changes nothing', () => {
    expect(reduce(WELCOME, { type: 'narrow' }, ctx)).toEqual(WELCOME);
    expect(reduce(quiz(2), { type: 'narrow' }, ctx)).toEqual(quiz(2));
    expect(reduce(CLOSED, { type: 'narrow' }, ctx)).toEqual(CLOSED);
  });

  it('done next closes', () => {
    expect(reduce(DONE_DEFERRED, { type: 'next' }, ctx)).toEqual(CLOSED);
  });

  it('done back returns to the survey intro when deferred, else the last question', () => {
    expect(reduce(DONE_DEFERRED, { type: 'back' }, ctx)).toEqual(SURVEY);
    expect(reduce(DONE, { type: 'back' }, ctx)).toEqual(quiz(6));
  });

  it('start from closed opens welcome', () => {
    expect(reduce(CLOSED, { type: 'start' }, ctx)).toEqual(WELCOME);
  });
});

describe('resume', () => {
  const first = (over: Partial<FlowContext> = {}): FlowContext => ({
    mode: 'first',
    narrow: false,
    tourAvailable: true,
    replaySurvey: true,
    ...over,
  });
  const resume = (step: string, question: number | null | undefined, ctx = first()) =>
    reduce(CLOSED, { type: 'resume', step, question }, ctx);

  it('restores each stored step', () => {
    expect(resume('welcome', null)).toEqual(WELCOME);
    expect(resume('tour:home', null)).toEqual(TOUR);
    expect(resume('survey-intro', null)).toEqual(SURVEY);
    expect(resume('quiz', 4)).toEqual(quiz(4));
    expect(resume('done', null)).toEqual(DONE);
  });

  it('falls back to question 1 when the stored question is invalid', () => {
    for (const question of [null, undefined, 0, 7, 2.5, -1]) {
      expect(resume('quiz', question)).toEqual(quiz(1));
    }
  });

  it('turns tour:home into the survey intro without a tour', () => {
    expect(resume('tour:home', null, first({ tourAvailable: false }))).toEqual(SURVEY);
    expect(resume('tour:home', null, first({ narrow: true }))).toEqual(SURVEY);
  });

  it('opens welcome for an unknown step', () => {
    expect(resume('library', null)).toEqual(WELCOME);
  });

  it('never resumes in replay mode or from an open screen', () => {
    expect(resume('quiz', 3, first({ mode: 'replay' }))).toEqual(CLOSED);
    expect(reduce(TOUR, { type: 'resume', step: 'quiz', question: 3 }, first())).toEqual(TOUR);
  });
});

describe('replay without missing answers', () => {
  const ctx = (over: Partial<FlowContext> = {}): FlowContext => ({
    mode: 'replay',
    narrow: false,
    tourAvailable: true,
    replaySurvey: false,
    ...over,
  });

  it('goes welcome, tour, done', () => {
    const afterWelcome = reduce(WELCOME, { type: 'start' }, ctx());
    expect(afterWelcome).toEqual(TOUR);
    expect(reduce(afterWelcome, { type: 'next' }, ctx())).toEqual(DONE);
  });

  it('goes welcome to done without a tour', () => {
    expect(reduce(WELCOME, { type: 'start' }, ctx({ narrow: true }))).toEqual(DONE);
  });

  it('done back returns to the tour, or welcome without one', () => {
    expect(reduce(DONE, { type: 'back' }, ctx())).toEqual(TOUR);
    expect(reduce(DONE, { type: 'back' }, ctx({ tourAvailable: false }))).toEqual(WELCOME);
  });

  it('a narrow event on the tour closes', () => {
    expect(reduce(TOUR, { type: 'narrow' }, ctx())).toEqual(CLOSED);
  });
});

describe('replay that still asks the survey', () => {
  it('goes from the tour to the survey intro', () => {
    const ctx: FlowContext = {
      mode: 'replay',
      narrow: false,
      tourAvailable: true,
      replaySurvey: true,
    };
    expect(reduce(TOUR, { type: 'next' }, ctx)).toEqual(SURVEY);
  });
});

describe('progressOf', () => {
  it('maps each screen to the stored step', () => {
    expect(progressOf(WELCOME)).toEqual({ step: 'welcome' });
    expect(progressOf(TOUR)).toEqual({ step: 'tour:home' });
    expect(progressOf(SURVEY)).toEqual({ step: 'survey-intro' });
    expect(progressOf(quiz(5, true))).toEqual({ step: 'quiz', question: 5 });
    expect(progressOf(DONE_DEFERRED)).toEqual({ step: 'done' });
    expect(progressOf(CLOSED)).toBeNull();
  });
});

describe('journeySteps and doneSteps', () => {
  const base = (mode: FlowContext['mode'], over: Partial<FlowContext> = {}): FlowContext => ({
    mode,
    narrow: false,
    tourAvailable: true,
    replaySurvey: true,
    ...over,
  });

  it('lists the interface and the survey in first mode', () => {
    expect(journeySteps(base('first'))).toEqual(['interface', 'survey']);
  });

  it('drops the interface without a tour', () => {
    expect(journeySteps(base('first', { narrow: true }))).toEqual(['survey']);
    expect(journeySteps(base('first', { tourAvailable: false }))).toEqual(['survey']);
  });

  it('drops the survey for a replay without missing answers but not for first mode', () => {
    expect(journeySteps(base('replay', { replaySurvey: false }))).toEqual(['interface']);
    expect(journeySteps(base('first', { replaySurvey: false }))).toEqual(['interface', 'survey']);
  });

  it('done leaves the survey out when it was deferred', () => {
    expect(doneSteps(base('first'), true)).toEqual(['interface']);
    expect(doneSteps(base('first'), false)).toEqual(['interface', 'survey']);
  });
});
