import '@testing-library/jest-dom/vitest';

Object.assign(URL, {
  revokeObjectURL: vi.fn(),
});

afterEach(() => {
  if (typeof URL.revokeObjectURL !== 'function') {
    URL.revokeObjectURL = vi.fn();
  }
});
