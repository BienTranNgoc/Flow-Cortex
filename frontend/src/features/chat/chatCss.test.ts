import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// vitest runs from the app directory
const read = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');
const chatCss = read('src/features/chat/chat.css');
const declaring = chatCss + read('src/styles/tokens.css') + read('src/app/base.css');

describe('chat.css', () => {
  it('only uses custom properties that something declares', () => {
    const declared = new Set([...declaring.matchAll(/(--[\w-]+)\s*:/g)].map((match) => match[1]));
    const used = [...chatCss.matchAll(/var\((--[\w-]+)/g)].map((match) => match[1] as string);

    expect(used.filter((name) => !declared.has(name))).toEqual([]);
  });
});
