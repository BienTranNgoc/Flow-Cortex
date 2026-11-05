import { describeLocaleParity, type LocaleTree } from '@flow-cortex/i18n/testing';
import i18n from '../lib/i18n';

describeLocaleParity(
  import.meta.glob<LocaleTree>('./*/*.json', { eager: true, import: 'default' }),
  i18n,
);
