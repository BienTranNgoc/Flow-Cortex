import { routeFocusTarget } from './routeFocus';

describe('routeFocusTarget', () => {
  it.each([
    [{ state: { focusHeading: true }, hasHeading: true }, 'page'],
    [{ state: null, hasHeading: true }, 'heading'],
    [{ state: null, hasHeading: false }, 'main'],
    [{ state: { other: 1 }, hasHeading: false }, 'main'],
  ] as const)('%j focuses %s', (input, expected) => {
    expect(routeFocusTarget(input)).toBe(expected);
  });
});
