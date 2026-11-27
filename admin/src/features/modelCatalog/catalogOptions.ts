import type { CatalogModel, ModelDescriptor } from '@flow-cortex/api-client';

/** The values the database CHECK constraints accept; the server is the arbiter, these only fill the selects. */
export const CATEGORIES = [
  'ingest',
  'analyze',
  'generate',
  'transform',
  'present',
  'review',
  'deliver',
  'control',
] as const;

export const UI_GROUPS = [
  'input',
  'generate',
  'edit',
  'replace',
  'analyze',
  'deliver',
  'control',
] as const;

export const MODES = [
  'text',
  'image',
  'video',
  'audio',
  'transform',
  'deliver',
  'ingest',
  'control',
] as const;

export const ICON_PATTERN = /^[a-z0-9-]{1,64}$/;
export const MODEL_KEY_PATTERN = /^[A-Za-z0-9._:-]+(\/[A-Za-z0-9._:-]+)*$/;
export const PROVIDER_KEY_PATTERN = /^[a-z0-9._-]{1,64}$/;
export const MAX_MODEL_KEY = 128;
export const MAX_LABEL = 128;

export type DescriptorTone = 'success' | 'warning' | 'error';

/** Status pill tone of a descriptor: stable is fine, preview is a caution, deprecated is refused by the server. */
export function descriptorTone(status: ModelDescriptor['status']): DescriptorTone {
  if (status === 'stable') return 'success';
  if (status === 'preview') return 'warning';
  return 'error';
}

/** A row's label for the vendor and maker columns: both when they differ, one when only one is set. */
export function labelPair(row: Pick<CatalogModel, 'vendorLabel' | 'makerLabel'>): string {
  const parts = [row.vendorLabel, row.makerLabel].filter((part): part is string => !!part);
  return parts.filter((part, index) => parts.indexOf(part) === index).join(' / ');
}
