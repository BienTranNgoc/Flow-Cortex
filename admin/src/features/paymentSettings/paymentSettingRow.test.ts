import type { PaymentSettingView } from '@flow-cortex/api-client';
import { draftError, draftOf, needsReason, requestValue, rowMode } from './paymentSettingRow';

const MODE: PaymentSettingView = {
  key: 'payment.region_gate.mode',
  group: 'region',
  type: 'ENUM',
  value: 'shadow',
  source: 'CONFIGURED',
  storedValue: null,
  configuredValue: 'shadow',
  defaultValue: 'shadow',
  dangerous: true,
  options: ['off', 'shadow', 'enforce'],
  min: null,
  max: null,
};

const LIMIT: PaymentSettingView = {
  ...MODE,
  key: 'payment.test.limit',
  type: 'INTEGER',
  value: 10,
  dangerous: false,
  options: [],
  min: 1,
  max: 100,
};

const FLAG: PaymentSettingView = {
  ...MODE,
  key: 'payment.test.flag',
  type: 'SWITCH',
  value: false,
  options: [],
};

describe('rowMode', () => {
  it('is read-only without the update permission and editable with it', () => {
    expect(rowMode(MODE, false)).toBe('readOnly');
    expect(rowMode(MODE, true)).toBe('editable');
  });
});

describe('draftError', () => {
  it('accepts an option exactly and refuses anything else', () => {
    expect(draftError(MODE, 'enforce')).toBeNull();
    expect(draftError(MODE, 'OFF')).toBe('options');
    expect(draftError(MODE, 'enforce ')).toBe('options');
    expect(draftError(MODE, '')).toBe('options');
  });

  it('refuses an integer outside its bounds or not whole', () => {
    expect(draftError(LIMIT, '50')).toBeNull();
    expect(draftError(LIMIT, ' 50 ')).toBeNull();
    expect(draftError(LIMIT, '1')).toBeNull();
    expect(draftError(LIMIT, '100')).toBeNull();
    for (const draft of ['0', '101', '1.5', '1e2', 'abc', '-3', '99999999999999999999']) {
      expect(draftError(LIMIT, draft)).toBe('range');
    }
  });

  it('names a blank integer draft on its own', () => {
    expect(draftError(LIMIT, '')).toBe('blank');
    expect(draftError(LIMIT, '   ')).toBe('blank');
  });

  it('accepts a negative integer when the bounds allow it', () => {
    const signed = { ...LIMIT, min: -5, max: 5 };
    expect(draftError(signed, '-5')).toBeNull();
    expect(draftError(signed, '-6')).toBe('range');
  });

  it('accepts only true or false for a switch', () => {
    expect(draftError(FLAG, 'true')).toBeNull();
    expect(draftError(FLAG, 'false')).toBeNull();
    expect(draftError(FLAG, 'yes')).toBe('options');
  });
});

describe('needsReason', () => {
  it('is true only for a dangerous setting', () => {
    expect(needsReason(MODE)).toBe(true);
    expect(needsReason(LIMIT)).toBe(false);
  });
});

describe('draft and request value', () => {
  it('starts from the value in force and sends the type the server expects', () => {
    expect(draftOf(MODE)).toBe('shadow');
    expect(draftOf(FLAG)).toBe('false');
    expect(requestValue(MODE, 'enforce')).toBe('enforce');
    expect(requestValue(LIMIT, ' 50 ')).toBe(50);
    expect(requestValue(FLAG, 'true')).toBe(true);
  });
});
