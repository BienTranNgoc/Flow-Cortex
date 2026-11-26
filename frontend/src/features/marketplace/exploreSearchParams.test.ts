import {
  hasFilters,
  isCanonical,
  limitTerms,
  parse,
  serialize,
  type ExploreState,
} from './exploreSearchParams';
import { LABELLED_STEP_TYPES } from './stepTypeLabels';

const params = (search: string) => new URLSearchParams(search);

const DEFAULTS: ExploreState = {
  q: '',
  types: [],
  sort: 'trending',
  sortChosen: false,
  tab: 'discover',
};

describe('exploreSearchParams', () => {
  it('emptyAddressIsDefaults', () => {
    expect(parse(params(''))).toEqual(DEFAULTS);
  });

  it('readsQTypeSortTab', () => {
    expect(parse(params('q=video&type=tts&type=translate&sort=newest&tab=saved'))).toEqual({
      q: 'video',
      types: ['tts', 'translate'],
      sort: 'newest',
      sortChosen: true,
      tab: 'saved',
    });
  });

  it('unknownSortFallsBackToTrending', () => {
    expect(parse(params('sort=hot'))).toEqual(DEFAULTS);
    expect(parse(params('sort='))).toEqual(DEFAULTS);
    expect(parse(params('sort=TOPRATED')).sortChosen).toBe(false);
  });

  it('repeatedTypeKeptDeduplicated', () => {
    expect(parse(params('type=tts&type=&type=translate&type=tts&type=%20')).types).toEqual([
      'tts',
      'translate',
    ]);
  });

  it('unknownTabIsDiscover', () => {
    expect(parse(params('tab=favourites')).tab).toBe('discover');
    expect(parse(params('tab=')).tab).toBe('discover');
  });

  it('writeOmitsUnchosenValues', () => {
    expect(serialize(DEFAULTS).toString()).toBe('');
    expect(serialize({ ...DEFAULTS, q: '  ', types: [] }).toString()).toBe('');
  });

  it('writeKeepsAnExplicitlyChosenTrending', () => {
    const chosen = serialize({ ...DEFAULTS, sort: 'trending', sortChosen: true });
    expect(chosen.toString()).toBe('sort=trending');
    expect(parse(chosen).sortChosen).toBe(true);
  });

  it('writesEveryChosenValueAndReadsItBack', () => {
    const state: ExploreState = {
      q: 'tạo video',
      types: ['tts', 'workflow.call'],
      sort: 'topRated',
      sortChosen: true,
      tab: 'saved',
    };
    expect(parse(serialize(state))).toEqual(state);
  });

  it('hasFiltersWhenQTypeOrAChosenSortSet', () => {
    expect(hasFilters(DEFAULTS)).toBe(false);
    expect(hasFilters({ ...DEFAULTS, q: 'video' })).toBe(true);
    expect(hasFilters({ ...DEFAULTS, types: ['tts'] })).toBe(true);
    expect(hasFilters({ ...DEFAULTS, sort: 'trending', sortChosen: true })).toBe(true);
    expect(hasFilters({ ...DEFAULTS, tab: 'saved' })).toBe(false);
  });

  it('dropsAnOverlongSearchAndMoreThanTenTypes', () => {
    expect(parse(params(`q=${'a'.repeat(101)}`)).q).toBe('');
    expect(parse(params(`q=${'a'.repeat(100)}`)).q).toHaveLength(100);
    const eleven = LABELLED_STEP_TYPES.slice(0, 11)
      .map((type) => `type=${type}`)
      .join('&');
    expect(parse(params(eleven)).types).toHaveLength(10);
  });

  it('dropsATypeTheEngineDoesNotKnow', () => {
    expect(parse(params('type=teleport&type=tts&type=TTS&type=workflow.call')).types).toEqual([
      'tts',
      'workflow.call',
    ]);
    expect(isCanonical(params('type=teleport'))).toBe(false);
  });

  it('dropsASearchOfMoreThanEightTerms', () => {
    expect(parse(params('q=a+b+c+d+e+f+g+h')).q).toBe('a b c d e f g h');
    expect(parse(params('q=a+b+c+d+e+f+g+h+i')).q).toBe('');
    expect(parse(params('q=a%C2%A0b++c%09d+e+f+g+h+i')).q).toBe('');
    expect(isCanonical(params('q=a+b+c+d+e+f+g+h+i'))).toBe(false);
  });

  it('cutsATypedSearchAfterItsEighthTerm', () => {
    expect(limitTerms('a b c d e f g h')).toBe('a b c d e f g h');
    expect(limitTerms('a b c d e f g h ')).toBe('a b c d e f g h ');
    expect(limitTerms('a b c d e f g h i')).toBe('a b c d e f g h');
    expect(limitTerms('a  b\tc d e f g h   i j')).toBe('a  b\tc d e f g h');
    expect(limitTerms('')).toBe('');
  });

  it('anAddressWithADroppedValueIsNotCanonical', () => {
    expect(isCanonical(params('sort=hot&type='))).toBe(false);
    expect(isCanonical(params('type=tts&type=tts'))).toBe(false);
    expect(isCanonical(params('sort=newest&q=video'))).toBe(true);
    expect(isCanonical(params(''))).toBe(true);
  });
});
