import type { PaymentSettingView } from '@flow-cortex/api-client';

export type RowMode = 'editable' | 'readOnly';
export type DraftError = 'options' | 'range' | 'blank';

type RowSetting = Pick<
  PaymentSettingView,
  'type' | 'options' | 'min' | 'max' | 'dangerous' | 'value'
>;

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;
const WHOLE_NUMBER = /^-?\d+$/;

export function rowMode(_setting: RowSetting, canUpdate: boolean): RowMode {
  return canUpdate ? 'editable' : 'readOnly';
}

/** The text a control starts from: the value in force. */
export function draftOf(setting: RowSetting): string {
  return String(setting.value);
}

/**
 * Why `draft` could not be saved, mirroring the server's parse so Save stays disabled before a
 * round trip; the server still validates every write.
 */
export function draftError(setting: RowSetting, draft: string): DraftError | null {
  if (setting.type === 'SWITCH') {
    return draft === 'true' || draft === 'false' ? null : 'options';
  }
  if (setting.type === 'ENUM') {
    return setting.options.includes(draft) ? null : 'options';
  }
  const text = draft.trim();
  if (text === '') return 'blank';
  if (!WHOLE_NUMBER.test(text)) return 'range';
  const value = Number(text);
  const inRange = value >= (setting.min ?? INT_MIN) && value <= (setting.max ?? INT_MAX);
  return inRange ? null : 'range';
}

export function needsReason(setting: Pick<PaymentSettingView, 'dangerous'>): boolean {
  return setting.dangerous;
}

/** The JSON value for a draft that has no `draftError`. */
export function requestValue(setting: RowSetting, draft: string): string | boolean | number {
  if (setting.type === 'SWITCH') return draft === 'true';
  if (setting.type === 'INTEGER') return Number(draft.trim());
  return draft;
}
