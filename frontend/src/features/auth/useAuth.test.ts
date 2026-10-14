import { getToken, setToken } from '@flow-cortex/api-client';
import { stubApi } from '../../test/fakeApi';
import { logout } from './useAuth';

describe('logout', () => {
  beforeEach(() => setToken('signed-in', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('calls the API, then clears the token', async () => {
    const calls = stubApi({ 'POST /api/v1/auth/logout': { status: 204 } });

    await logout();

    expect(calls.map((call) => `${call.method} ${call.path}`)).toEqual([
      'POST /api/v1/auth/logout',
    ]);
    expect(getToken()).toBeNull();
  });

  it('clears the token when the server answers 5xx', async () => {
    stubApi({ 'POST /api/v1/auth/logout': { status: 500, body: { status: 500 } } });

    await logout();

    expect(getToken()).toBeNull();
  });

  it('clears the token when the network call fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));

    await logout();

    expect(getToken()).toBeNull();
  });
});
