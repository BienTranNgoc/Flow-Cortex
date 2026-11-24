import type { CheckResponse } from '@flow-cortex/api-client';
import { describe, expect, it } from 'vitest';
import { googleAuthorizeUrl } from './oauth';
import {
  authorizeParams,
  consentRedirect,
  consentView,
  grantFailure,
  rememberConsentQuery,
  restoreConsentQuery,
  type ConsentStorage,
} from './oauthConsent';

const CALLBACK = 'https://claude.ai/api/mcp/auth_callback';

function check(overrides: Partial<CheckResponse> = {}): CheckResponse {
  return {
    clientId: 'mcp_ab12',
    clientName: 'Claude',
    redirectUri: CALLBACK,
    scope: 'mcp',
    eligible: true,
    error: null,
    ...overrides,
  };
}

function memoryStorage(initial: Record<string, string> = {}): ConsentStorage & {
  data: Map<string, string>;
} {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (key) => data.get(key) ?? null,
    setItem: (key, value) => void data.set(key, value),
    removeItem: (key) => void data.delete(key),
  };
}

const throwingStorage: ConsentStorage = {
  getItem: () => {
    throw new Error('blocked');
  },
  setItem: () => {
    throw new Error('blocked');
  },
  removeItem: () => {
    throw new Error('blocked');
  },
};

describe('consentRedirect', () => {
  it('adds error and state to a redirect URI without a query', () => {
    expect(consentRedirect(CALLBACK, { error: 'access_denied', state: 'xyz' })).toBe(
      `${CALLBACK}?error=access_denied&state=xyz`,
    );
  });

  it('joins an existing query with an ampersand', () => {
    expect(
      consentRedirect('https://a.example/cb?tenant=7', { error: 'access_denied', state: 'xyz' }),
    ).toBe('https://a.example/cb?tenant=7&error=access_denied&state=xyz');
  });

  it('leaves out state when there is none', () => {
    expect(consentRedirect(CALLBACK, { error: 'invalid_scope' })).toBe(
      `${CALLBACK}?error=invalid_scope`,
    );
    expect(consentRedirect(CALLBACK, { error: 'invalid_scope', state: '' })).toBe(
      `${CALLBACK}?error=invalid_scope`,
    );
  });

  it('encodes the values', () => {
    expect(consentRedirect(CALLBACK, { error: 'access_denied', state: 'a b&c=d/é' })).toBe(
      `${CALLBACK}?error=access_denied&state=a%20b%26c%3Dd%2F%C3%A9`,
    );
  });
});

describe('consentView', () => {
  it('is loading while the check is pending', () => {
    expect(consentView({ phase: 'pending' })).toBe('loading');
  });

  it('reads a 400 as an invalid link and a 503 as unavailable', () => {
    expect(consentView({ phase: 'failed', status: 400 })).toBe('invalidLink');
    expect(consentView({ phase: 'failed', status: 503 })).toBe('unavailable');
  });

  it('reads any other failure as a failed check', () => {
    expect(consentView({ phase: 'failed', status: 500 })).toBe('failed');
    expect(consentView({ phase: 'failed', status: undefined })).toBe('failed');
  });

  it('says not eligible when the account may not consent', () => {
    expect(consentView({ phase: 'answered', check: check({ eligible: false }) })).toBe(
      'notEligible',
    );
  });

  it('sends a redirectable fault back to the client, whoever looks', () => {
    expect(consentView({ phase: 'answered', check: check({ error: 'invalid_scope' }) })).toBe(
      'redirectError',
    );
    expect(
      consentView({
        phase: 'answered',
        check: check({ error: 'invalid_request', eligible: false }),
      }),
    ).toBe('redirectError');
  });

  it('is ready for a valid request of an eligible account', () => {
    expect(consentView({ phase: 'answered', check: check() })).toBe('ready');
  });
});

describe('authorizeParams', () => {
  it('passes the eight authorization fields and drops client_name', () => {
    const search = new URLSearchParams({
      response_type: 'code',
      client_id: 'mcp_ab12',
      redirect_uri: CALLBACK,
      code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
      code_challenge_method: 'S256',
      scope: 'mcp',
      state: 'xyz',
      resource: 'https://app.nodi.vn/api/v1',
      client_name: 'Nodi Support',
      utm: 'x',
    });
    expect(authorizeParams(search)).toEqual({
      response_type: 'code',
      client_id: 'mcp_ab12',
      redirect_uri: CALLBACK,
      code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
      code_challenge_method: 'S256',
      scope: 'mcp',
      state: 'xyz',
      resource: 'https://app.nodi.vn/api/v1',
    });
  });

  it('omits the fields the link does not carry', () => {
    expect(authorizeParams(new URLSearchParams('client_id=mcp_ab12'))).toEqual({
      client_id: 'mcp_ab12',
    });
  });
});

describe('grantFailure', () => {
  it('names what a refused grant means for the page', () => {
    expect(grantFailure(403, 'STEP_UP_REQUIRED')).toBe('stepUp');
    expect(grantFailure(403, 'PAT_NOT_ELIGIBLE')).toBe('notEligible');
    expect(grantFailure(409, 'PAT_LIMIT_REACHED')).toBe('limitReached');
    expect(grantFailure(400, 'oauth.invalidRequest')).toBe('invalidLink');
    expect(grantFailure(503, undefined)).toBe('unavailable');
    expect(grantFailure(500, undefined)).toBe('failed');
    expect(grantFailure(403, 'PAT_NOT_ALLOWED')).toBe('failed');
  });
});

describe('Google sign-in from a consent link', () => {
  const returnToOf = (url: string) =>
    new URL(url, 'https://app.example').searchParams.get('return_to');

  it('returns to the consent path alone when its query is too long for the return path', () => {
    const long = `/oauth/authorize?client_id=mcp_ab12&state=${'x'.repeat(700)}`;
    expect(returnToOf(googleAuthorizeUrl(true, long))).toBe('/oauth/authorize');
  });

  it('keeps a short consent query and every other return path as it is', () => {
    const short = '/oauth/authorize?client_id=mcp_ab12&state=xyz';
    expect(returnToOf(googleAuthorizeUrl(true, short))).toBe(short);
    const other = `/workflows?filter=${'x'.repeat(700)}`;
    expect(returnToOf(googleAuthorizeUrl(true, other))).toBe(other);
  });
});

describe('remembering the consent query across sign-in', () => {
  const longQuery = `?${new URLSearchParams({ client_id: 'mcp_ab12', state: 'x'.repeat(700) })}`;

  it('stores a 700-character query and restores it once for a page opened without one', () => {
    const storage = memoryStorage();
    rememberConsentQuery(storage, longQuery);
    expect(restoreConsentQuery(storage, '')).toBe(longQuery);
    expect(restoreConsentQuery(storage, '')).toBeNull();
    expect(storage.data.size).toBe(0);
  });

  it('keeps nothing for an empty query', () => {
    const storage = memoryStorage();
    rememberConsentQuery(storage, '');
    expect(storage.data.size).toBe(0);
  });

  it('ignores and clears the stored query when the page opens with its own', () => {
    const storage = memoryStorage();
    rememberConsentQuery(storage, longQuery);
    expect(restoreConsentQuery(storage, '?client_id=mcp_other')).toBeNull();
    expect(storage.data.size).toBe(0);
  });

  it('restores nothing and does not throw when storage is blocked', () => {
    expect(() => rememberConsentQuery(throwingStorage, longQuery)).not.toThrow();
    expect(restoreConsentQuery(throwingStorage, '')).toBeNull();
  });
});
