import { parseSettingInput } from './settingInput';

const MAX_INT = 2147483647;

describe('parseSettingInput', () => {
  it('reads a whole number inside the bounds', () => {
    expect(parseSettingInput('30', 1, MAX_INT)).toEqual({ ok: true, value: 30 });
    expect(parseSettingInput(' 7 ', 1, MAX_INT)).toEqual({ ok: true, value: 7 });
    expect(parseSettingInput('1', 1, MAX_INT)).toEqual({ ok: true, value: 1 });
    expect(parseSettingInput('2147483647', 1, MAX_INT)).toEqual({ ok: true, value: MAX_INT });
  });

  it.each(['', '   ', '12.5', '1e3', 'abc', '-', '+5', '0x10', '0', '2147483648', '-3'])(
    'refuses %j and names the bounds',
    (raw) => {
      expect(parseSettingInput(raw, 1, MAX_INT)).toEqual({ ok: false, min: 1, max: MAX_INT });
    },
  );

  it('refuses a number above a lower ceiling', () => {
    expect(parseSettingInput('101', 1, 100)).toEqual({ ok: false, min: 1, max: 100 });
  });
});
