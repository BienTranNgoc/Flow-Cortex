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

  describe('load_template_missing_input', () => {
    // Exactly what the backend TemplateLoad writes for two missing fields.
    const fromServer = 'Required input missing: productName, productImage.';

    it('names the missing fields in English', () => {
      expect(stepErrorSentence('load_template_missing_input', fromServer, false, en, 'en')).toBe(
        'Missing required template inputs: productName, productImage.',
      );
    });

    it('names the missing fields in Vietnamese without English prose', () => {
      const sentence = stepErrorSentence('load_template_missing_input', fromServer, false, vi, 'vi');

      expect(sentence).toBe('Còn thiếu đầu vào bắt buộc của mẫu: productName, productImage.');
      expect(sentence).not.toContain('Required');
    });

    it('does not entity-escape field names', () => {
      expect(
        stepErrorSentence('load_template_missing_input', 'Required input missing: a&b.', false, en, 'en'),
      ).toBe('Missing required template inputs: a&b.');
    });

    it.each([undefined, 'Something else went wrong', 'Required input missing: .'])(
      'falls back to the plain sentence when the message is %s',
      (message) => {
        expect(stepErrorSentence('load_template_missing_input', message, false, en, 'en')).toBe(
          'Some required inputs for the template are missing.',
        );
        expect(stepErrorSentence('load_template_missing_input', message, false, vi, 'vi')).toBe(
          viWorkflow.stepError.load_template_missing_input,
        );
      },
    );
  });
});
