import { ApiError } from '@flow-cortex/api-client';
import { startProblem } from './startProblem';

const AUDIO_SIZE = 'Files of this type must be at most 100 MB. Accepted: .mp3, .wav, .m4a.';
const NOT_VIDEO = "This file isn't a valid video file. Accepted: .mp4, .mov, .webm, .mkv.";
const TYPE =
  "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.";
const INCOMPLETE_DETAIL = "This workflow can't run yet: step 2 is not fully configured.";

function refused(status: number, body: Record<string, unknown>) {
  return new ApiError(status, { status, ...body });
}

describe('startProblem', () => {
  it.each([
    ['size', AUDIO_SIZE],
    ['fileName', TYPE],
    ['uploadId', NOT_VIDEO],
    ['uploadIds', 'Files of this type must be at most 10 MB. Accepted: .jpg, .jpeg, .png, .webp.'],
  ])('shows a 400 on errors.%s as the file field message', (field, message) => {
    expect(startProblem(refused(400, { errors: { [field]: message } }))).toEqual({
      kind: 'field',
      message,
    });
  });

  it('turns a 422 WORKFLOW_INCOMPLETE into the summary, every failing step in order, and the first one', () => {
    const problem = startProblem(
      refused(422, {
        code: 'WORKFLOW_INCOMPLETE',
        detail: INCOMPLETE_DETAIL,
        errors: {
          step4: 'The profile is blank',
          step2: 'Reads from step 5, which is not an earlier step',
        },
      }),
    );

    expect(problem).toEqual({
      kind: 'incomplete',
      summary: INCOMPLETE_DETAIL,
      firstOrder: 2,
      steps: [
        { order: 2, reason: 'Reads from step 5, which is not an earlier step' },
        { order: 4, reason: 'The profile is blank' },
      ],
    });
  });

  it('writes the summary from the first failing step when the 422 has no detail', () => {
    const problem = startProblem(
      refused(422, {
        code: 'WORKFLOW_INCOMPLETE',
        errors: { step3: 'The configuration is not a JSON object' },
      }),
    );

    expect(problem).toMatchObject({
      kind: 'incomplete',
      summary: "This workflow can't run yet: step 3 is not fully configured.",
      firstOrder: 3,
    });
  });

  it('keeps the summary and marks no step when the 422 names none', () => {
    expect(
      startProblem(refused(422, { code: 'WORKFLOW_INCOMPLETE', detail: INCOMPLETE_DETAIL })),
    ).toEqual({ kind: 'incomplete', summary: INCOMPLETE_DETAIL, firstOrder: null, steps: [] });
  });

  it('turns a 402 INSUFFICIENT_CREDITS into the credits sentence and offers "Run anyway" on a positive balance', () => {
    expect(
      startProblem(
        refused(402, {
          code: 'INSUFFICIENT_CREDITS',
          detail: 'Not enough credits',
          balance: 30,
          required: 60,
          shortfall: 30,
        }),
      ),
    ).toEqual({
      kind: 'credits',
      message: 'Not enough credits: you have 30, this run needs 60 (short 30).',
      canRunAnyway: true,
    });
  });

  it('offers no "Run anyway" when the 402 says the balance is 0 or lower', () => {
    expect(
      startProblem(
        refused(402, { code: 'INSUFFICIENT_CREDITS', balance: 0, required: 60, shortfall: 60 }),
      ),
    ).toEqual({
      kind: 'credits',
      message: 'Not enough credits: you have 0, this run needs 60 (short 60).',
      canRunAnyway: false,
    });
  });

  it('groups large credit amounts the en-US way', () => {
    expect(
      startProblem(
        refused(402, {
          code: 'INSUFFICIENT_CREDITS',
          balance: 1500,
          required: 12000,
          shortfall: 10500,
        }),
      ),
    ).toMatchObject({
      message: 'Not enough credits: you have 1,500, this run needs 12,000 (short 10,500).',
    });
  });

  it('shows the detail of a 402 that does not carry the three amounts as an alert', () => {
    expect(
      startProblem(refused(402, { code: 'INSUFFICIENT_CREDITS', detail: 'Not enough credits' })),
    ).toEqual({ kind: 'alert', message: 'Not enough credits' });
  });

  it('shows the detail of any other refusal as an alert', () => {
    expect(startProblem(refused(503, { detail: 'Media engine is currently disabled' }))).toEqual({
      kind: 'alert',
      message: 'Media engine is currently disabled',
    });
    expect(startProblem(refused(422, { code: 'SOMETHING_ELSE', detail: 'Not now' }))).toEqual({
      kind: 'alert',
      message: 'Not now',
    });
  });

  it('falls back to the given sentence when there is no detail or the error is not from the API', () => {
    expect(startProblem(new ApiError(500, undefined))).toEqual({
      kind: 'alert',
      message: 'Could not start the run.',
    });
    expect(
      startProblem(new TypeError('Failed to fetch'), 'Workflow execution is currently disabled.'),
    ).toEqual({
      kind: 'alert',
      message: 'Workflow execution is currently disabled.',
    });
  });
});
