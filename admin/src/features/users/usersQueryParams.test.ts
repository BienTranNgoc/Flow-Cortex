import { DEFAULT_USERS_PARAMS, parseUsersParams, toSearchParams } from './usersQueryParams';

const parse = (query: string) => parseUsersParams(new URLSearchParams(query));

describe('parseUsersParams', () => {
  it('returns the defaults for an empty query', () => {
    expect(parse('')).toEqual({ q: '', role: '', state: '', sort: 'newest', page: 0 });
  });

  it.each([
    ['state=bogus', 'state'],
    ['role=owner', 'role'],
    ['role=ADMIN-X', 'role'],
    ['sort=x', 'sort'],
    ['page=-3', 'page'],
    ['page=abc', 'page'],
    ['page=1.5', 'page'],
    ['page=99999999999', 'page'],
  ] as const)('falls back to the default for %s', (query, key) => {
    expect(parse(query)[key]).toEqual(DEFAULT_USERS_PARAMS[key]);
  });

  it('keeps every valid value', () => {
    expect(parse('q=nguyen&role=CREATOR&state=locked&sort=email&page=2')).toEqual({
      q: 'nguyen',
      role: 'CREATOR',
      state: 'locked',
      sort: 'email',
      page: 2,
    });
  });

  it('trims q and cuts it to 100 characters', () => {
    expect(parse('q=%20%20nguyen%20%20').q).toBe('nguyen');
    expect(parse(`q=${'a'.repeat(150)}`).q).toHaveLength(100);
  });
});

describe('toSearchParams', () => {
  it('omits defaults', () => {
    expect(toSearchParams(DEFAULT_USERS_PARAMS).toString()).toBe('');
  });

  it('round-trips a full set', () => {
    const full = { q: 'nguyen', role: 'CREATOR', state: 'locked', sort: 'email', page: 2 } as const;
    expect(parseUsersParams(toSearchParams(full))).toEqual(full);
  });
});
