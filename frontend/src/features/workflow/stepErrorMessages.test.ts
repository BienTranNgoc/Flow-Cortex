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

  it('REVIEW_EXPIRED reads the member sentence in either language, not the server message', () => {
    const fromServer = 'The server wording';

    expect(stepErrorSentence('REVIEW_EXPIRED', fromServer, false, en, 'en')).toBe(
      'No one decided in time, so the run stopped.',
    );
    const sentence = stepErrorSentence('REVIEW_EXPIRED', fromServer, false, vi, 'vi');
    expect(sentence).toBe(viWorkflow.stepError.REVIEW_EXPIRED);
    expect(sentence).not.toBe(fromServer);
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

  it('covers every errorCode the backend writes on a step', () => {
    const backendCodes = [
      'input_rejected',
      'probe_failed',
      'audio_extraction_failed',
      'audio_alignment_failed',
      'audio_alignment_out_of_range',
      'provider_capability_unavailable',
      'provider_request_failed',
      'provider_response_invalid',
      'provider_timeout',
      'contract_violation',
      'run_timeout',
      'duration_exceeded',
      'no_audio_stream',
      'sibling_failed',
      'queue_timeout',
      'lease_expired',
      'media_disabled',
      'store_unavailable',
      'translation_invalid',
      'dub_disabled',
      'speech_too_long',
      'provider_quota_exceeded',
      'provider_unavailable',
      'provider_busy',
      'INSUFFICIENT_CREDITS',
      'APPROVAL_REJECTED',
      'load_template_invalid_input',
      'load_template_not_found',
      'load_template_invalid_template',
      'load_template_missing_input',
    ];

    expect(backendCodes.filter((code) => !KNOWN_STEP_ERROR_CODES.includes(code))).toEqual([]);
  });

  it('tells the member a busy provider pool is temporary, in both languages', () => {
    const fromServer = 'Too many videos are being processed right now. Try again in a few minutes.';

    expect(stepErrorSentence('provider_busy', fromServer, false, en, 'en')).toBe(
      enWorkflow.stepError.provider_busy,
    );
    expect(stepErrorSentence('provider_busy', fromServer, false, vi, 'vi')).toBe(
      viWorkflow.stepError.provider_busy,
    );
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
