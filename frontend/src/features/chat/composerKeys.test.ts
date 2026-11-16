import { isSubmitKey, nextSendKey } from './composerKeys';

describe('isSubmitKey', () => {
  it('submits on a plain Enter', () => {
    expect(isSubmitKey({ key: 'Enter', shiftKey: false, isComposing: false })).toBe(true);
  });

  it('keeps Shift+Enter as a newline', () => {
    expect(isSubmitKey({ key: 'Enter', shiftKey: true, isComposing: false })).toBe(false);
  });

  it('never submits while an IME composition is open', () => {
    expect(isSubmitKey({ key: 'Enter', shiftKey: false, isComposing: true })).toBe(false);
  });

  it('ignores other keys', () => {
    expect(isSubmitKey({ key: 'a', shiftKey: false, isComposing: false })).toBe(false);
  });
});

describe('nextSendKey', () => {
  const fresh = () => 'fresh-key';

  it('mints a key for the first send', () => {
    expect(nextSendKey(null, 'hello', fresh)).toEqual({ text: 'hello', key: 'fresh-key' });
  });

  it('reuses the key when the same text is sent again after a failure', () => {
    const first = { text: 'hello', key: 'old-key' };
    expect(nextSendKey(first, 'hello', fresh)).toBe(first);
  });

  it('mints a new key once the text differs', () => {
    expect(nextSendKey({ text: 'hello', key: 'old-key' }, 'hello again', fresh)).toEqual({
      text: 'hello again',
      key: 'fresh-key',
    });
  });
});
