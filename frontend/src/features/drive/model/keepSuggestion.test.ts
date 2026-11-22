import type { SuggestionResponse } from '@flow-cortex/api-client';
import { keepDefaults, keepRequest } from './keepSuggestion';

const BASE = {
  tooLong: false,
  workflowName: 'Product video',
  runId: 'run-1',
  at: '2026-11-10T08:00:00Z',
} as const;

function candidate(overrides: Partial<SuggestionResponse> = {}): SuggestionResponse {
  return {
    ...BASE,
    id: 'step-1/clip.mp4',
    kind: 'video',
    previewText: 'clip.mp4',
    runOutput: { runId: 'run-1', stepOrder: 3, name: 'clip.mp4' },
    ...overrides,
  };
}

function textCandidate(overrides: Partial<SuggestionResponse> = {}): SuggestionResponse {
  return {
    ...BASE,
    id: 'step-2',
    kind: 'text',
    previewText: 'A warm, direct voice',
    textValue: 'A warm, direct voice that never shouts.',
    ...overrides,
  };
}

describe('keepSuggestion', () => {
  it("builds a text item from a text candidate's textValue", () => {
    const request = keepRequest(textCandidate(), 'Brand voice', 'brand_voice_text');

    expect(request).toEqual({
      name: 'Brand voice',
      contentType: 'text',
      semanticRole: 'brand_voice_text',
      textValue: 'A warm, direct voice that never shouts.',
    });
  });

  it('builds a media item from runOutput with contentType equal to the kind', () => {
    const request = keepRequest(candidate(), 'Intro clip', 'driver_video');

    expect(request).toEqual({
      name: 'Intro clip',
      contentType: 'video',
      semanticRole: 'driver_video',
      runOutput: { runId: 'run-1', stepOrder: 3, name: 'clip.mp4' },
    });
    expect(keepRequest(candidate({ kind: 'audio' }), 'Voice', 'audio_asset')).toMatchObject({
      contentType: 'audio',
    });
  });

  it('refuses a tooLong candidate', () => {
    const tooLong: SuggestionResponse = { ...BASE, id: 'step-3', kind: 'text', previewText: 'Long', tooLong: true };

    expect(keepRequest(tooLong, 'Name', 'brand_voice_text')).toEqual({ refusal: 'tooLong' });
  });

  it('names a media candidate after its output without the extension, cut to 160', () => {
    const named = candidate({ runOutput: { runId: 'run-1', stepOrder: 3, name: 'final cut.v2.mp4' } });
    const long = candidate({ runOutput: { runId: 'run-1', stepOrder: 3, name: `${'a'.repeat(200)}.mp4` } });
    const bare = candidate({ runOutput: { runId: 'run-1', stepOrder: 3, name: 'output' } });
    const hidden = candidate({ runOutput: { runId: 'run-1', stepOrder: 3, name: '.mp4' } });

    expect(keepDefaults(named).name).toBe('final cut.v2');
    expect(keepDefaults(long).name).toBe('a'.repeat(160));
    expect(keepDefaults(bare).name).toBe('output');
    expect(keepDefaults(hidden).name).toBe('.mp4');
  });

  it('names a text candidate after its preview cut to 160', () => {
    const long = textCandidate({ previewText: 'b'.repeat(200) });
    const emoji = textCandidate({ previewText: `${'c'.repeat(159)}😀` });

    expect(keepDefaults(textCandidate()).name).toBe('A warm, direct voice');
    expect(keepDefaults(long).name).toBe('b'.repeat(160));
    expect(keepDefaults(emoji).name).toBe('c'.repeat(159));
  });

  it('preselects the role hint, else the first role of the kind', () => {
    const hinted = candidate({ kind: 'image', roleHint: 'product_photo' });
    const unhinted = candidate({ kind: 'image' });
    const input = textCandidate({ roleHint: 'audience_note' });

    expect(keepDefaults(hinted).role).toBe('product_photo');
    expect(keepDefaults(unhinted).role).toBe('logo');
    expect(keepDefaults(input).role).toBe('audience_note');
    expect(keepDefaults(textCandidate()).role).toBe('brand_voice_text');
    expect(keepDefaults(hinted).roles).toEqual([
      'logo',
      'product_photo',
      'reference_face',
      'persona_image',
      'image_asset',
    ]);
  });
});
