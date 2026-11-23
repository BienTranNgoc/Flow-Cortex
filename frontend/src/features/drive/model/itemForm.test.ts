// Mirrors DriveItemRules of the backend: names are trimmed and 1-160 long, texts are 1-32,768 long,
// both counted in UTF-16 code units like Java's String.length().
import { editItemSchema, nameSchema, NAME_MAX, textItemSchema, TEXT_MAX, textSchema } from './itemForm';

describe('itemForm', () => {
  it('accepts a 160-character name with spaces around it and refuses 161', () => {
    const parsed = nameSchema.safeParse(`  ${'a'.repeat(NAME_MAX)}  `);

    expect(parsed.success).toBe(true);
    expect(parsed.success && parsed.data).toBe('a'.repeat(NAME_MAX));
    expect(nameSchema.safeParse('a'.repeat(NAME_MAX + 1)).success).toBe(false);
  });

  it('refuses a name that is empty after trimming', () => {
    expect(nameSchema.safeParse('').success).toBe(false);
    expect(nameSchema.safeParse('   \n\t ').success).toBe(false);
  });

  it('accepts a 32,768-character text and refuses 32,769', () => {
    expect(textSchema.safeParse('x'.repeat(TEXT_MAX)).success).toBe(true);
    expect(textSchema.safeParse('x'.repeat(TEXT_MAX + 1)).success).toBe(false);
  });

  it('counts an emoji as two characters like the server', () => {
    const emoji = '😀';

    expect(emoji.length).toBe(2);
    expect(textSchema.safeParse(emoji.repeat(TEXT_MAX / 2)).success).toBe(true);
    expect(textSchema.safeParse(`${emoji.repeat(TEXT_MAX / 2)}a`).success).toBe(false);
    expect(nameSchema.safeParse(emoji.repeat(NAME_MAX / 2)).success).toBe(true);
    expect(nameSchema.safeParse(`${emoji.repeat(NAME_MAX / 2)}a`).success).toBe(false);
  });

  it('refuses an empty text', () => {
    expect(textSchema.safeParse('').success).toBe(false);
  });

  it('keeps a text untouched, spaces and line breaks included', () => {
    const text = '  first line\n\nsecond line  ';
    const parsed = textSchema.safeParse(text);

    expect(parsed.success && parsed.data).toBe(text);
  });

  it('checks name, role and text together for a text item', () => {
    const valid = { name: ' Brand voice ', semanticRole: 'brand_voice_text', textValue: 'Warm.' };

    expect(textItemSchema.safeParse(valid).success).toBe(true);
    expect(textItemSchema.safeParse({ ...valid, name: '' }).success).toBe(false);
    expect(textItemSchema.safeParse({ ...valid, textValue: '' }).success).toBe(false);
    expect(textItemSchema.safeParse({ ...valid, semanticRole: '' }).success).toBe(false);
  });

  it('lets an edit leave the text out and still checks the name and the role', () => {
    expect(editItemSchema.safeParse({ name: 'New', semanticRole: 'logo' }).success).toBe(true);
    expect(editItemSchema.safeParse({ name: 'New', semanticRole: 'logo', textValue: '' }).success).toBe(false);
    expect(editItemSchema.safeParse({ name: ' ', semanticRole: 'logo' }).success).toBe(false);
  });
});
