import { refreshAccessToken } from './session';
import { getToken, setToken } from './tokenStore';

const PERSISTENT_KEY = 'flowcortex.auth.token';

type LockCallback = () => Promise<unknown>;

/** A Web Locks stand-in: requests for one name run strictly one after the other. */
function fakeLocks() {
  let tail: Promise<unknown> = Promise.resolve();
  const events: string[] = [];
  const request = vi.fn((_name: string, callback: LockCallback) => {
    const run = tail.then(async () => {
      events.push('enter');
      try {
        return await callback();
      } finally {
        events.push('exit');
      }
    });
    tail = run.catch(() => undefined);
    return run;
  });
  /** Another tab takes the lock and keeps it until `release()`. */
  const hold = () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => (release = resolve));
    void request('flowcortex-auth-refresh', () => gate);
    return { release };
  };
  return { locks: { request }, events, hold };
}

describe('refreshAccessToken across tabs', () => {
  afterEach(() => {
    setToken(null);
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it('runs the refresh request inside the lock, after the lock holder finished', async () => {
    const { locks, events, hold } = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    let fetchedWhileLocked = false;
    const fetchStub = vi.fn(async () => {
      fetchedWhileLocked = events.at(-1) === 'enter';
      return new Response(JSON.stringify({ accessToken: 'mine', expiresIn: 900 }), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchStub);

    const otherTab = hold();
    const pending = refreshAccessToken();
    await Promise.resolve();
    expect(fetchStub).not.toHaveBeenCalled();

    otherTab.release();

    expect(await pending).toBe('mine');
    expect(fetchedWhileLocked).toBe(true);
    expect(locks.request).toHaveBeenCalledWith('flowcortex-auth-refresh', expect.any(Function));
  });

  it('uses the token another tab stored meanwhile, without calling the server', async () => {
    const { locks, hold } = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    const fetchStub = vi.fn();
    vi.stubGlobal('fetch', fetchStub);
    setToken('old', true);

    const otherTab = hold();
    const pending = refreshAccessToken();
    window.localStorage.setItem(PERSISTENT_KEY, 'rotated-by-other-tab');
    otherTab.release();

    expect(await pending).toBe('rotated-by-other-tab');
    expect(getToken()).toBe('rotated-by-other-tab');
    expect(fetchStub).not.toHaveBeenCalled();
  });

  it('still refreshes in-tab when navigator.locks is unavailable', async () => {
    vi.stubGlobal('navigator', {});
    const fetchStub = vi.fn(
      async () => new Response(JSON.stringify({ accessToken: 'fallback', expiresIn: 900 }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchStub);

    const [a, b] = await Promise.all([refreshAccessToken(), refreshAccessToken()]);

    expect([a, b]).toEqual(['fallback', 'fallback']);
    expect(fetchStub).toHaveBeenCalledTimes(1);
  });
});
