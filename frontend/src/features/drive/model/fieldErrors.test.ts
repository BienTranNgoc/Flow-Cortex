import { splitFieldErrors } from './fieldErrors';

describe('splitFieldErrors', () => {
  it('puts errors of visible fields under their fields', () => {
    const split = splitFieldErrors({ name: 'Name is too long', textValue: 'Text is empty' }, ['name', 'textValue']);

    expect(split.byField).toEqual({ name: 'Name is too long', textValue: 'Text is empty' });
    expect(split.rest).toEqual([]);
  });

  it('returns every other error sentence in rest', () => {
    const split = splitFieldErrors(
      { name: 'Name is too long', payload: 'Send a file or a text, not both', runOutput: 'That output is gone' },
      ['name', 'semanticRole'],
    );

    expect(split.byField).toEqual({ name: 'Name is too long' });
    expect(split.rest).toEqual(['Send a file or a text, not both', 'That output is gone']);
  });

  it('returns empty parts for no errors', () => {
    expect(splitFieldErrors({}, ['name'])).toEqual({ byField: {}, rest: [] });
  });

  it('shows a sentence once when two hidden fields share it', () => {
    const split = splitFieldErrors({ payload: 'Not allowed', contentType: 'Not allowed' }, ['name']);

    expect(split.rest).toEqual(['Not allowed']);
  });
});
