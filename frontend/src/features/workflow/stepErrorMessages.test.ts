import i18n from '../../lib/i18n';
import viWorkflow from '../../locales/vi/workflow.json';
import { stepErrorSentence } from './stepErrorMessages';

const en = i18n.getFixedT('en');
const vi = i18n.getFixedT('vi');

describe('stepErrorSentence', () => {
  it('shows how long the voice-over ran when it is longer than the 10-second video', () => {
    const fromServer =
      'The approved script reads for 13.4 s, longer than the 10 s video. Start a new run with a shorter script.';
    expect(stepErrorSentence('speech_too_long', fromServer, true, en, 'en')).toBe(fromServer);
  });

  it('falls back to the general sentence when the server sent no message', () => {
    expect(stepErrorSentence('speech_too_long', undefined, true, en, 'en')).toBe(
      'The approved script is too long for the 10-second video. Start a new run with a shorter script.',
    );
  });

  describe('speech_too_long with a server message', () => {
    const fromServer =
      'The approved script reads for 11.2 s, longer than the 10 s video. Start a new run with a shorter script.';

    it('reads the Vietnamese generic sentence in Vietnamese, without the seconds', () => {
      const sentence = stepErrorSentence('speech_too_long', fromServer, true, vi, 'vi');

      expect(sentence).toBe(viWorkflow.stepError.speech_too_long);
      expect(sentence).not.toContain('11.2');
    });

    it('reads the server message verbatim in English', () => {
      expect(stepErrorSentence('speech_too_long', fromServer, true, en, 'en')).toBe(fromServer);
    });
  });
});
