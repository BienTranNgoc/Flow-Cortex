// Mirrors DriveRoles.table() of the backend (backend/modules/drive/.../domain/DriveRoles.java).
import { contentTypeOf, defaultRoleOf, ROLE_TABLE, rolesOf } from './driveRoles';

describe('driveRoles', () => {
  it('maps every role to exactly one content type', () => {
    const roles = ROLE_TABLE.map(([role]) => role);

    expect(roles).toHaveLength(11);
    expect(new Set(roles).size).toBe(roles.length);
    for (const [role, type] of ROLE_TABLE) {
      expect(contentTypeOf(role)).toBe(type);
    }
  });

  it('lists the three text roles in table order', () => {
    expect(rolesOf('text')).toEqual(['brand_voice_text', 'company_bio', 'audience_note']);
  });

  it('lists logo, product_photo, reference_face, persona_image, image_asset for image', () => {
    expect(rolesOf('image')).toEqual([
      'logo',
      'product_photo',
      'reference_face',
      'persona_image',
      'image_asset',
    ]);
  });

  it('has no content type for an unknown role', () => {
    expect(contentTypeOf('hero_banner')).toBeUndefined();
    expect(contentTypeOf('')).toBeUndefined();
    expect(contentTypeOf('toString')).toBeUndefined();
  });

  it('defaults a kind without a hint to its first role (text → brand_voice_text)', () => {
    expect(defaultRoleOf('text')).toBe('brand_voice_text');
    expect(defaultRoleOf('image')).toBe('logo');
    expect(defaultRoleOf('video')).toBe('driver_video');
    expect(defaultRoleOf('audio')).toBe('audio_asset');
  });

  it('keeps a hint that belongs to the kind and ignores one that does not', () => {
    expect(defaultRoleOf('image', 'product_photo')).toBe('product_photo');
    expect(defaultRoleOf('image', 'audio_asset')).toBe('logo');
    expect(defaultRoleOf('text', 'nonsense')).toBe('brand_voice_text');
  });
});
