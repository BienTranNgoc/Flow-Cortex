/** Every `errorCode` the server writes on a step, with the sentence a member reads for it. */
const SENTENCES: Record<string, string> = {
  input_rejected: "We couldn't use that uploaded file. Upload it again.",
  probe_failed:
    "We couldn't read that file as audio or video. Upload a video or audio file (for example .mp4).",
  lease_expired: 'The media engine stopped responding. Try running again.',
  queue_timeout: 'The media engine was too busy to start this in time. Try running again.',
  run_timeout: 'This file took too long to process and was stopped. Try a shorter file.',
  media_disabled: 'The media engine is switched off right now.',
  contract_violation: 'The media engine rejected this job. Try again later.',
  speech_too_long:
    'The approved script is too long for the 10-second video. Start a new run with a shorter script.',
  APPROVAL_REJECTED: 'You rejected this content, so the run stopped.',
};

/** The reaper closes inline steps with these codes too, and those never reached the media engine. */
const REAPED_CODES: ReadonlySet<string> = new Set([
  'queue_timeout',
  'lease_expired',
  'run_timeout',
]);
const REAPED_INLINE = 'This step stopped before it finished. Try running again.';

/** Codes whose server message is written for members and carries specifics the sentence lacks. */
const MEMBER_MESSAGE_CODES: ReadonlySet<string> = new Set(['speech_too_long']);

export const NO_MESSAGE = 'This step stopped with an error.';

/**
 * A step failure in the member's words. A failure without a known code (an engine error such as an
 * unknown step type) carries a message the server already wrote for members, so it is shown as is.
 * Unless the step is known to run on the media engine, a reaped step gets the neutral sentence.
 */
export function stepErrorSentence(
  code: string | undefined,
  message: string | undefined,
  remote: boolean,
): string {
  if (code && REAPED_CODES.has(code) && !remote) {
    return REAPED_INLINE;
  }
  if (code && MEMBER_MESSAGE_CODES.has(code) && message) {
    return message;
  }
  return (code ? SENTENCES[code] : undefined) ?? message ?? NO_MESSAGE;
}
