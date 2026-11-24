import type { CreateDriveItemRequestContentType } from '@flow-cortex/api-client';

export type ContentType = CreateDriveItemRequestContentType;

export const CONTENT_TYPES: readonly ContentType[] = ['text', 'image', 'video', 'audio'];

/** Every semantic role with the one content type it belongs to, in the server's table order. */
export const ROLE_TABLE: readonly (readonly [role: string, type: ContentType])[] = [
  ['brand_voice_text', 'text'],
  ['company_bio', 'text'],
  ['audience_note', 'text'],
  ['logo', 'image'],
  ['product_photo', 'image'],
  ['reference_face', 'image'],
  ['persona_image', 'image'],
  ['image_asset', 'image'],
  ['driver_video', 'video'],
  ['video_asset', 'video'],
  ['audio_asset', 'audio'],
];

export function rolesOf(type: ContentType): string[] {
  return ROLE_TABLE.filter(([, owner]) => owner === type).map(([role]) => role);
}

export function contentTypeOf(role: string): ContentType | undefined {
  return ROLE_TABLE.find(([candidate]) => candidate === role)?.[1];
}

/** The hint when it is a role of `kind`, else the kind's first role. */
export function defaultRoleOf(kind: ContentType, hint?: string): string {
  const roles = rolesOf(kind);
  return hint !== undefined && roles.includes(hint) ? hint : (roles[0] as string);
}
