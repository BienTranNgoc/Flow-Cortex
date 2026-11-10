import { ApiError } from '@flow-cortex/api-client';
import i18n from '../../lib/i18n';
import { chatProblem, chatProblemCode } from './chatProblem';

const t = i18n.getFixedT('en');

function refused(status: number, code: string, extra: Record<string, unknown> = {}) {
  return new ApiError(status, { status, code, detail: `server says ${code}`, ...extra } as never);
}

describe('chatProblemCode', () => {
  it('reads the code, else the last segment of a problem type', () => {
    expect(chatProblemCode(refused(404, 'chat-conversation-not-found'))).toBe(
      'chat-conversation-not-found',
    );
    expect(
      chatProblemCode(
        new ApiError(409, { type: 'https://flow-cortex.dev/problems/chat-turn-in-progress' }),
      ),
    ).toBe('chat-turn-in-progress');
  });

  it('is undefined for a failure that is not a problem response', () => {
    expect(chatProblemCode(new Error('offline'))).toBeUndefined();
    expect(chatProblemCode(new ApiError(500, undefined))).toBeUndefined();
  });
});

describe('chatProblem', () => {
  it('says the conversation no longer exists on chat-conversation-not-found', () => {
    expect(chatProblem(refused(404, 'chat-conversation-not-found'), t, 'en')).toEqual({
      kind: 'not-found',
      message: 'This conversation no longer exists.',
    });
  });

  it('says chat is turned off on chat-disabled', () => {
    expect(chatProblem(refused(503, 'chat-disabled'), t, 'en')).toEqual({
      kind: 'disabled',
      message: 'Chat is turned off for now.',
    });
  });

  it('shows the server detail once, without a second resume line, for a quota refusal', () => {
    const shown = chatProblem(
      refused(429, 'chat-quota', { resumeAt: '2026-11-08T17:00:00Z' }),
      t,
      'en',
    );
    expect(shown.kind).toBe('quota');
    expect(shown.message).toBe('server says chat-quota');
    expect(shown.resume).toBeUndefined();
  });

  it('adds the resume line only when the server sent no detail of its own', () => {
    const shown = chatProblem(
      new ApiError(429, { code: 'chat-quota', resumeAt: '2026-11-08T17:00:00Z' } as never),
      t,
      'en',
    );
    expect(shown.message).toBe("You've reached today's chat limit.");
    expect(shown.resume).toMatch(/^You can chat again .*00:00/);
  });

  it('falls back to the local quota sentence when the server sends no detail or time', () => {
    const shown = chatProblem(new ApiError(429, { code: 'chat-quota' }), t, 'en');
    expect(shown).toEqual({ kind: 'quota', message: "You've reached today's chat limit." });
  });

  it('ignores a resume time that is not a date', () => {
    const shown = chatProblem(refused(429, 'chat-quota', { resumeAt: 'soon' }), t, 'en');
    expect(shown.resume).toBeUndefined();
  });

  it('says the assistant is still answering on chat-turn-in-progress', () => {
    expect(chatProblem(refused(409, 'chat-turn-in-progress'), t, 'en')).toEqual({
      kind: 'in-progress',
      message: 'The assistant is still answering.',
    });
  });

  it('follows the language it is given', () => {
    expect(chatProblem(refused(404, 'chat-conversation-not-found'), i18n.getFixedT('vi'), 'vi'))
      .toMatchObject({ message: 'Cuộc trò chuyện này không còn tồn tại.' });
  });

  it('shows the server detail for any other refusal, else a generic line', () => {
    expect(chatProblem(refused(422, 'idempotency-key-reused'), t, 'en')).toEqual({
      kind: 'alert',
      message: 'server says idempotency-key-reused',
    });
    expect(chatProblem(new Error('offline'), t, 'en')).toEqual({
      kind: 'alert',
      message: 'Something went wrong. Please try again.',
    });
  });
});
