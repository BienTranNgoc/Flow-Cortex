/** Maps a workflow name (case-insensitive, partial) to the icon that best represents it. */
export type WorkflowIconKey =
  | 'video'
  | 'tiktok'
  | 'terminal'
  | 'alert'
  | 'cart'
  | 'audio'
  | 'image'
  | 'spark';

export function iconForWorkflow(name: string | undefined): WorkflowIconKey {
  if (!name) return 'spark';
  const lower = name.toLowerCase();
  if (/(tiktok|douyin|repurpose|short)/.test(lower)) return 'tiktok';
  if (/(product|video|render|advert)/.test(lower)) return 'video';
  if (/(echo|terminal|code|script)/.test(lower)) return 'terminal';
  if (/(fail|error|broken|deliberate)/.test(lower)) return 'alert';
  if (/(cart|shop|product\s*video|product-video)/.test(lower)) return 'cart';
  if (/(audio|sound|voice|tts|speech)/.test(lower)) return 'audio';
  if (/(image|photo|thumb)/.test(lower)) return 'image';
  return 'spark';
}
