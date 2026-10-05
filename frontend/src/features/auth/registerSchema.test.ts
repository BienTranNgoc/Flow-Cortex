import { registerSchema } from './registerSchema';

function form(password: string) {
  return { name: 'New Person', email: 'new@example.com', password, confirmPassword: password };
}

describe('registerSchema', () => {
  it('accepts a password of exactly 72 UTF-8 bytes', () => {
    expect(registerSchema.safeParse(form('ế'.repeat(24))).success).toBe(true);
  });

  it('rejects a password over 72 UTF-8 bytes even when it is under 72 characters', () => {
    const result = registerSchema.safeParse(form('ế'.repeat(25)));

    expect(result.success).toBe(false);
    expect(result.error?.issues.map((issue) => issue.path.join('.'))).toContain('password');
  });
});
