/** Every step type the engine registers, plus the two a person decides. */
export const LABELLED_STEP_TYPES: readonly string[] = [
  'echo',
  'fail',
  'generate_script',
  'generate_storyboard',
  'generate_speech',
  'generate_video',
  'fetch_video',
  'ingest_video',
  'ingest_image',
  'probe',
  'extract_audio',
  'align_audio',
  'render_video',
  'separate_audio',
  'translate',
  'tts',
  'load_template',
  'workflow.call',
  'connector.call',
  'iterate',
  'APPROVAL_CHECKPOINT',
  'qa.review',
];

/** The catalogue key of a type's label, or null for a type with none (shown by its raw name). */
export function stepTypeLabelKey(type: string): string | null {
  return LABELLED_STEP_TYPES.includes(type)
    ? `workflow.marketplace.stepTypes.${type.replaceAll('.', '_')}`
    : null;
}
