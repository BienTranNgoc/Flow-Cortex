import { ensureSession, login, logout, setClientId, setToken } from '@flow-cortex/api-client';
import { configureAdminClient } from './configureClient';

describe('configureAdminClient', () => {
  afterEach(() => {
    setClientId(null);
    setToken(null);
    vi.unstubAllGlobals();
  });

  function stubFetch(): string[][] {
    const seen: string[][] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        seen.push([url, new Headers(init?.headers).get('X-FlowCortex-Client') ?? '']);
        return new Response(JSON.stringify({ accessToken: 'a', expiresIn: 900 }), { status: 200 });
      }),
    );
    return seen;
  }

  it('marks login, refresh and logout as the admin client', async () => {
    const seen = stubFetch();
    configureAdminClient();

    await login({ email: 'a@b.c', password: 'x' });
    await ensureSession();
    await logout();

    expect(seen).toEqual([
      ['/api/v1/auth/login', 'admin'],
      ['/api/v1/auth/refresh', 'admin'],
      ['/api/v1/auth/logout', 'admin'],
    ]);
  });
});
