import i18n from '../../lib/i18n';
import enWorkflow from '../../locales/en/workflow.json';
import viWorkflow from '../../locales/vi/workflow.json';
import { KNOWN_STEP_ERROR_CODES, stepErrorSentence } from './stepErrorMessages';

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
      enWorkflow.stepError.speech_too_long,
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

  it('renders localized messages for sibling_failed in English and Vietnamese', () => {
    expect(stepErrorSentence('sibling_failed', 'sibling failed', true, en, 'en')).toBe(
      enWorkflow.stepError.sibling_failed,
    );
    expect(stepErrorSentence('sibling_failed', 'sibling failed', true, vi, 'vi')).toBe(
      viWorkflow.stepError.sibling_failed,
    );
  });

  it('maps every known step error code in both English and Vietnamese without leaking raw text', () => {
    const rawWorkerError =
      'transient retry budget exhausted after 3 redeliveries: provider masked transient failure: upstream_temporarily_unavailable';

    for (const code of KNOWN_STEP_ERROR_CODES) {
      const viText = stepErrorSentence(code, rawWorkerError, true, vi, 'vi');
      expect(viText).toBeTruthy();
      expect(viText).not.toContain(rawWorkerError);
      expect(viText).not.toContain('workflow.stepError.');

      const enText = stepErrorSentence(code, rawWorkerError, true, en, 'en');
      expect(enText).toBeTruthy();
      if (code !== 'speech_too_long') {
        expect(enText).not.toContain(rawWorkerError);
      }
      expect(enText).not.toContain('workflow.stepError.');
    }
  });

  it('uses AI-step context copy instead of file/media-engine copy for AI and Product Video steps', () => {
    const aiSteps = [
      'generate_script',
      'generate_storyboard',
      'generate_speech',
      'generate_video',
      'render_video',
    ];
    const codes = [
      'run_timeout',
      'queue_timeout',
      'lease_expired',
      'contract_violation',
      'media_disabled',
    ] as const;

    for (const stepType of aiSteps) {
      for (const code of codes) {
        const viText = stepErrorSentence(code, 'raw error', true, vi, 'vi', stepType);
        expect(viText).toBe(viWorkflow.stepError.context.ai[code]);
        expect(viText.toLowerCase()).not.toContain('tệp');
        expect(viText.toLowerCase()).not.toContain('bộ xử lý media');

        const enText = stepErrorSentence(code, 'raw error', true, en, 'en', stepType);
        expect(enText).toBe(enWorkflow.stepError.context.ai[code]);
        expect(enText.toLowerCase()).not.toContain('file');
        expect(enText.toLowerCase()).not.toContain('media engine');
      }
    }

    expect(stepErrorSentence('run_timeout', 'raw error', true, vi, 'vi', 'probe')).toBe(
      viWorkflow.stepError.run_timeout,
    );
  });

  it('falls back to noMessage for unknown or missing codes and never leaks server message', () => {
    const rawError = 'NullPointerException at com.flowcortex.InternalService:42';
    expect(stepErrorSentence('unknown_future_code', rawError, true, en, 'en')).toBe(
      enWorkflow.stepError.noMessage,
    );
    expect(stepErrorSentence('unknown_future_code', rawError, true, vi, 'vi')).toBe(
      viWorkflow.stepError.noMessage,
    );
    expect(stepErrorSentence(undefined, rawError, true, vi, 'vi')).toBe(
      viWorkflow.stepError.noMessage,
    );
  });
});
