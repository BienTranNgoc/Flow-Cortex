import { authHeaders, unwrap } from './client';
import { setClientId } from './clientId';
import { ensureSession } from './session';
import { login, logout, me } from './generated/flowCortexApi';
import { getToken, setToken } from './tokenStore';
import { setLocaleProvider } from './locale';

const PERSISTENT_KEY = 'flowcortex.auth.token';
const SESSION_KEY = 'flowcortex.auth.sessionToken';

interface Call {
  path: string;
  method: string;
  authorization: string | null;
  client: string | null;
  language: string | null;
}

/** Stubs `fetch`; `answer` decides each reply from the request path and the bearer it carried. */
function stubFetch(answer: (call: Call) => Response | Promise<Response>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const call: Call = {
        path: input.split('?')[0] ?? input,
        method: init?.method ?? 'GET',
        authorization: new Headers(init?.headers).get('Authorization'),
        client: new Headers(init?.headers).get('X-FlowCortex-Client'),
        language: new Headers(init?.headers).get('Accept-Language'),
      };
      calls.push(call);
      return answer(call);
    }),
  );
  return calls;
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const refreshCalls = (calls: Call[]) => calls.filter((c) => c.path === '/api/v1/auth/refresh');

describe('customFetch', () => {
  afterEach(() => {
    setToken(null);
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it('refreshes once for parallel 401s and replays every failed request with the new token', async () => {
    setToken('old', true);
    const calls = stubFetch((call) => {
      if (call.path === '/api/v1/auth/refresh') return json(200, { accessToken: 'new', expiresIn: 900 });
      return call.authorization === 'Bearer new' ? json(200, { id: 'u1' }) : json(401, { status: 401 });
    });

    const results = await Promise.all([
      unwrap(me(authHeaders())),
      unwrap(me(authHeaders())),
    ]);

    expect(results).toEqual([{ id: 'u1' }, { id: 'u1' }]);
    expect(refreshCalls(calls)).toHaveLength(1);
    expect(calls.filter((c) => c.authorization === 'Bearer new')).toHaveLength(2);
    expect(getToken()).toBe('new');
  });

  it('keeps the persistence the user chose when it stores the refreshed token', async () => {
    setToken('old', false);
    stubFetch((call) =>
      call.path === '/api/v1/auth/refresh'
        ? json(200, { accessToken: 'new', expiresIn: 900 })
        : call.authorization === 'Bearer new'
          ? json(200, {})
          : json(401, {}),
    );

    await unwrap(me(authHeaders()));

    expect(window.sessionStorage.getItem(SESSION_KEY)).toBe('new');
    expect(window.localStorage.getItem(PERSISTENT_KEY)).toBeNull();
  });

  it('clears the token and surfaces the 401 when the refresh fails, without refreshing again', async () => {
    setToken('old', true);
    const calls = stubFetch(() => json(401, { status: 401 }));

    await expect(unwrap(me(authHeaders()))).rejects.toMatchObject({ status: 401 });

    expect(refreshCalls(calls)).toHaveLength(1);
    expect(calls.filter((c) => c.path === '/api/v1/auth/me')).toHaveLength(1);
    expect(getToken()).toBeNull();
  });

  it('does not refresh a second time when the replayed request is rejected again', async () => {
    setToken('old', true);
    const calls = stubFetch((call) =>
      call.path === '/api/v1/auth/refresh'
        ? json(200, { accessToken: 'new', expiresIn: 900 })
        : json(401, { status: 401 }),
    );

    await expect(unwrap(me(authHeaders()))).rejects.toMatchObject({ status: 401 });

    expect(refreshCalls(calls)).toHaveLength(1);
    expect(calls.filter((c) => c.path === '/api/v1/auth/me')).toHaveLength(2);
  });

  it('replays with the current token instead of refreshing when another request already renewed it', async () => {
    setToken('new', true);
    const calls = stubFetch((call) =>
      call.authorization === 'Bearer new' ? json(200, {}) : json(401, {}),
    );

    const late = await me({ headers: { Authorization: 'Bearer old' } });

    expect(late.status).toBe(200);
    expect(refreshCalls(calls)).toHaveLength(0);
  });

  it.each([
    ['login', () => login({ email: 'a@b.c', password: 'x' })],
    ['logout', () => logout()],
  ])('never refreshes after a 401 from %s', async (_name, call) => {
    const calls = stubFetch(() => json(401, { status: 401 }));

    const response = await call();

    expect(response.status).toBe(401);
    expect(calls).toHaveLength(1);
  });

  describe('client id', () => {
    afterEach(() => setClientId(null));

    const refreshingOnExpiry = (call: Call) => {
      if (call.path === '/api/v1/auth/refresh') return json(200, { accessToken: 'new', expiresIn: 900 });
      if (call.path === '/api/v1/auth/me') return call.authorization === 'Bearer new' ? json(200, {}) : json(401, {});
      return json(200, { accessToken: 'a', expiresIn: 900 });
    };

    it('marks login, refresh and logout with the configured client id and nothing else', async () => {
      setClientId('admin');
      setToken('old', true);
      const calls = stubFetch(refreshingOnExpiry);

      await login({ email: 'a@b.c', password: 'x' });
      await me(authHeaders());
      await logout();

      const marked = Object.fromEntries(calls.map((c) => [c.path, c.client]));
      expect(marked['/api/v1/auth/login']).toBe('admin');
      expect(marked['/api/v1/auth/refresh']).toBe('admin');
      expect(marked['/api/v1/auth/logout']).toBe('admin');
      expect(calls.filter((c) => c.path === '/api/v1/auth/me').map((c) => c.client)).toEqual([null, null]);
    });

    it('sends no client header from an app that never configured one', async () => {
      setToken('old', true);
      const calls = stubFetch(refreshingOnExpiry);

      await login({ email: 'a@b.c', password: 'x' });
      await me(authHeaders());
      await logout();

      expect(calls.length).toBeGreaterThan(3);
      expect(calls.every((c) => c.client === null)).toBe(true);
    });

    it('marks the bootstrap refresh made by ensureSession', async () => {
      setClientId('admin');
      const calls = stubFetch(() => json(200, { accessToken: 'boot', expiresIn: 900 }));

      await ensureSession();

      expect(refreshCalls(calls).map((c) => c.client)).toEqual(['admin']);
    });
  });

  describe('active language', () => {
    afterEach(() => setLocaleProvider(() => null));

    it('sends the provider language on the first request, the refresh and the replay', async () => {
      setLocaleProvider(() => 'en');
      setToken('old', true);
      const calls = stubFetch((call) => {
        if (call.path === '/api/v1/auth/refresh') return json(200, { accessToken: 'new', expiresIn: 900 });
        return call.authorization === 'Bearer new' ? json(200, {}) : json(401, {});
      });

      await unwrap(me(authHeaders()));

      expect(calls.map((c) => [c.path, c.authorization, c.language])).toEqual([
        ['/api/v1/auth/me', 'Bearer old', 'en'],
        ['/api/v1/auth/refresh', null, 'en'],
        ['/api/v1/auth/me', 'Bearer new', 'en'],
      ]);
    });

    it('sends no language header when the provider has none', async () => {
      setLocaleProvider(() => null);
      const calls = stubFetch(() => json(200, {}));

      await me();

      expect(calls.map((c) => c.language)).toEqual([null]);
    });
  });

  it('answers a 204 with no data', async () => {
    stubFetch(() => new Response(null, { status: 204 }));

    const response = await logout();

    expect(response.status).toBe(204);
    expect(response.data).toBeUndefined();
  });
});

describe('ensureSession', () => {
  afterEach(() => {
    setToken(null);
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it('exchanges the refresh cookie for an access token when none is stored', async () => {
    const calls = stubFetch(() => json(200, { accessToken: 'boot', expiresIn: 900 }));

    await ensureSession();

    expect(getToken()).toBe('boot');
    expect(refreshCalls(calls)).toHaveLength(1);
  });

  it('does nothing when a token is already stored', async () => {
    setToken('have', true);
    const calls = stubFetch(() => json(200, { accessToken: 'boot', expiresIn: 900 }));

    await ensureSession();

    expect(calls).toHaveLength(0);
    expect(getToken()).toBe('have');
  });

  it('stays signed out after one failed attempt', async () => {
    const calls = stubFetch(() => json(401, { status: 401 }));

    await ensureSession();

    expect(getToken()).toBeNull();
    expect(calls).toHaveLength(1);
  });
});
