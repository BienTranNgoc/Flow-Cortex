import { linkProblem } from './startRunSchema';

const REFUSED = { messageKey: 'workflow.validation.linkRequired' };

function linkOfLength(length: number): string {
  const prefix = 'https://x.example/';
  return prefix + 'a'.repeat(length - prefix.length);
}

describe('linkProblem', () => {
  it.each([
    ['a platform link', 'https://www.youtube.com/watch?v=abc'],
    ['a plain http link', 'http://x.example/a'],
    ['a link with surrounding spaces', '  https://cdn.example.com/clip.mp4  '],
    ['a link of exactly 2048 characters', linkOfLength(2048)],
  ])('accepts %s', (_name, link) => {
    expect(linkProblem(link)).toBeNull();
  });

  it.each([
    ['an empty text', ''],
    ['a blank text', '   '],
    ['plain text', 'hello'],
    ['another scheme', 'ftp://x.example/a.mp4'],
    ['a link with a password', 'https://user:pw@x.example/a.mp4'],
    ['a link with a space inside', 'http://h/a b'],
    ['a link with no host', 'https:///a'],
    ['a link of 2049 characters', linkOfLength(2049)],
  ])('refuses %s', (_name, link) => {
    expect(linkProblem(link)).toEqual(REFUSED);
  });
});
