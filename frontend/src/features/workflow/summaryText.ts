import type { TFunction } from 'i18next';
import type { OutputField } from './stepOutputFields';

/** The `video-summary.txt` body: which file it describes, then exactly the fields the page shows. */
export function summaryText(
  fileName: string | undefined,
  fields: readonly OutputField[],
  t: TFunction,
): string {
  const lines = fields.map((field) => `${field.label}: ${field.value}`);
  return [...(fileName ? [t('workflow.file', { name: fileName })] : []), ...lines]
    .map((line) => `${line}\n`)
    .join('');
}
