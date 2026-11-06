import '@testing-library/jest-dom/vitest';
import i18n from '../lib/i18n';

beforeAll(async () => {
  await i18n.changeLanguage('en');
});

Object.assign(URL, {
  revokeObjectURL: vi.fn(),
});

afterEach(() => {
  if (typeof URL.revokeObjectURL !== 'function') {
    URL.revokeObjectURL = vi.fn();
  }
});
