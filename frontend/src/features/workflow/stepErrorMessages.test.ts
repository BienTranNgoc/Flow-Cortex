import { stepErrorSentence } from './stepErrorMessages';

describe('stepErrorSentence', () => {
  it('shows how long the voice-over ran when it is longer than the 10-second video', () => {
    const fromServer =
      'The approved script reads for 13.4 s, longer than the 10 s video. Start a new run with a shorter script.';
    expect(stepErrorSentence('speech_too_long', fromServer, true)).toBe(fromServer);
  });

  it('falls back to the general sentence when the server sent no message', () => {
    expect(stepErrorSentence('speech_too_long', undefined, true)).toBe(
      'The approved script is too long for the 10-second video. Start a new run with a shorter script.',
    );
  });
});
