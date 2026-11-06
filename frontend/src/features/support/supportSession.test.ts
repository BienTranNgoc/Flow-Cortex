import { SUPPORT_SESSION_KEY, supportSessionId } from './supportSession';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

afterEach(() => {
  window.localStorage.clear();
  vi.unstubAllGlobals();
});

describe('supportSessionId', () => {
  it('makes a UUID v4 from getRandomValues where randomUUID is missing (insecure context)', () => {
    const real = globalThis.crypto;
    vi.stubGlobal('crypto', { getRandomValues: real.getRandomValues.bind(real) });

    const id = supportSessionId();

    expect(id).toMatch(UUID_V4);
    expect(window.localStorage.getItem(SUPPORT_SESSION_KEY)).toBe(id);
  });

  it('keeps the stored id', () => {
    const id = supportSessionId();

    expect(supportSessionId()).toBe(id);
  });
});
