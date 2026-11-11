import type { TFunction } from 'i18next';

/** i18next splits keys on dots, so the catalogue keys a setting by its key with dots as underscores. */
function slug(key: string): string {
  return key.replaceAll('.', '_');
}

export function settingLabel(t: TFunction, key: string): string {
  return t(`paymentSettings.settings.${slug(key)}.label`, { defaultValue: key });
}

export function settingDescription(t: TFunction, key: string): string {
  return t(`paymentSettings.settings.${slug(key)}.description`, { defaultValue: '' });
}

export function optionLabel(t: TFunction, key: string, option: string): string {
  return t(`paymentSettings.settings.${slug(key)}.options.${option}`, { defaultValue: option });
}

/** A stored or requested value as the operator reads it: an option's label, else the raw text. */
export function valueLabel(t: TFunction, key: string, value: string | boolean | number): string {
  if (typeof value === 'boolean')
    return t(value ? 'paymentSettings.switch.on' : 'paymentSettings.switch.off');
  return optionLabel(t, key, String(value));
}
