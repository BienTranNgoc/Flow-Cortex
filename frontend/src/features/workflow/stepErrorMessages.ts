import type { TFunction } from 'i18next';
import type { Locale } from '../../lib/i18n';

/** Every `errorCode` the server or worker writes on a step, mapped to a member-facing i18n key. */
const SENTENCE_KEYS: Record<string, string> = {
  input_rejected: 'workflow.stepError.input_rejected',
  probe_failed: 'workflow.stepError.probe_failed',
  audio_extraction_failed: 'workflow.stepError.audio_extraction_failed',
  audio_alignment_failed: 'workflow.stepError.audio_alignment_failed',
  audio_alignment_out_of_range: 'workflow.stepError.audio_alignment_out_of_range',
  duration_exceeded: 'workflow.stepError.duration_exceeded',
  no_audio_stream: 'workflow.stepError.no_audio_stream',
  lease_expired: 'workflow.stepError.lease_expired',
  queue_timeout: 'workflow.stepError.queue_timeout',
  run_timeout: 'workflow.stepError.run_timeout',
  media_disabled: 'workflow.stepError.media_disabled',
  contract_violation: 'workflow.stepError.contract_violation',
  speech_too_long: 'workflow.stepError.speech_too_long',
  provider_capability_unavailable: 'workflow.stepError.provider_capability_unavailable',
  provider_request_failed: 'workflow.stepError.provider_request_failed',
  provider_response_invalid: 'workflow.stepError.provider_response_invalid',
  provider_timeout: 'workflow.stepError.provider_timeout',
  provider_quota_exceeded: 'workflow.stepError.provider_quota_exceeded',
  provider_unavailable: 'workflow.stepError.provider_unavailable',
  sibling_failed: 'workflow.stepError.sibling_failed',
  APPROVAL_REJECTED: 'workflow.stepError.APPROVAL_REJECTED',
  INSUFFICIENT_CREDITS: 'workflow.stepError.INSUFFICIENT_CREDITS',
  dub_disabled: 'workflow.stepError.dub_disabled',
  store_unavailable: 'workflow.stepError.store_unavailable',
  translation_invalid: 'workflow.stepError.translation_invalid',
};

export const KNOWN_STEP_ERROR_CODES: readonly string[] = Object.keys(SENTENCE_KEYS);

const AI_STEP_TYPES: ReadonlySet<string> = new Set([
  'generate_script',
  'generate_storyboard',
  'generate_speech',
  'generate_video',
  'render_video',
]);

const AI_CONTEXT_KEYS: Record<string, string> = {
  run_timeout: 'workflow.stepError.context.ai.run_timeout',
  queue_timeout: 'workflow.stepError.context.ai.queue_timeout',
  lease_expired: 'workflow.stepError.context.ai.lease_expired',
  contract_violation: 'workflow.stepError.context.ai.contract_violation',
  media_disabled: 'workflow.stepError.context.ai.media_disabled',
};

/** The reaper closes inline steps with these codes too, and those never reached the media engine. */
const REAPED_CODES: ReadonlySet<string> = new Set([
  'queue_timeout',
  'lease_expired',
  'run_timeout',
]);
const REAPED_INLINE = 'workflow.stepError.reapedInline';

/** Codes whose server message is written for members and carries specifics the sentence lacks. */
const MEMBER_MESSAGE_CODES: ReadonlySet<string> = new Set(['speech_too_long']);

export const NO_MESSAGE = 'workflow.stepError.noMessage';

/**
 * Resolves a step failure into a member-facing sentence using one ordered rule table.
 * Unknown or missing codes fall back to `NO_MESSAGE` so raw server/worker errors never leak.
 */
export function stepErrorSentence(
  code: string | undefined,
  message: string | undefined,
  remote: boolean,
  t: TFunction,
  locale: Locale,
  stepType?: string,
): string {
  if (code && REAPED_CODES.has(code) && !remote) {
    return t(REAPED_INLINE);
  }
  if (code && MEMBER_MESSAGE_CODES.has(code) && message && locale === 'en') {
    return message;
  }
  if (code && stepType && AI_STEP_TYPES.has(stepType)) {
    const contextKey = AI_CONTEXT_KEYS[code];
    if (contextKey) {
      return t(contextKey);
    }
  }
  const key = code ? SENTENCE_KEYS[code] : undefined;
  if (key) {
    return t(key);
  }
  return t(NO_MESSAGE);
}
