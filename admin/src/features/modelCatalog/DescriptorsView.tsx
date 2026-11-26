import type { ModelDescriptor } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../app/EmptyState';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { descriptorTone } from './catalogOptions';
import { useModelDescriptors } from './useModelCatalog';

/** Thinking knob, plus its allowed values and default when the model has any. */
function thinkingText(descriptor: ModelDescriptor, label: (knob: string) => string): string {
  const { knob, values, defaultValue } = descriptor.thinking;
  if (knob === 'none') return label(knob);
  const list = values.join(', ');
  const base = list === '' ? label(knob) : `${label(knob)}: ${list}`;
  return defaultValue ? `${base} (${defaultValue})` : base;
}

/** Read-only list of the model descriptors the code ships. */
export function DescriptorsView() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const query = useModelDescriptors();
  const descriptors = query.data;
  const columns = {
    id: t('modelCatalog.descriptors.columns.id'),
    provider: t('modelCatalog.descriptors.columns.provider'),
    status: t('modelCatalog.descriptors.columns.status'),
    modalities: t('modelCatalog.descriptors.columns.modalities'),
    limits: t('modelCatalog.descriptors.columns.limits'),
    thinking: t('modelCatalog.descriptors.columns.thinking'),
  };
  const knob = (value: string) => t(`modelCatalog.knobs.${value}`);

  return (
    <section className="model-catalog-tab" aria-label={t('modelCatalog.tabs.descriptors')}>
      <p className="model-catalog-note">{t('modelCatalog.descriptors.intro')}</p>
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">{t('modelCatalog.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('modelCatalog.retry')}
          </button>
        </div>
      )}
      {query.isPending && (
        <output className="cortex-admin-loading">{t('modelCatalog.loading')}</output>
      )}
      {descriptors?.length === 0 && <EmptyState title={t('modelCatalog.descriptors.empty')} />}
      {descriptors && descriptors.length > 0 && (
        <TableRegion label={t('modelCatalog.descriptors.tableLabel')}>
          <table className="cortex-admin-table model-catalog-table">
            <thead>
              <tr>
                <th scope="col">{columns.id}</th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.provider}
                </th>
                <th scope="col">{columns.status}</th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.modalities}
                </th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.limits}
                </th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.thinking}
                </th>
              </tr>
            </thead>
            <tbody>
              {descriptors.map((descriptor) => (
                <tr key={`${descriptor.providerKey}/${descriptor.id}`}>
                  <td data-label={columns.id}>
                    <code className="cortex-admin-table-job-id">{descriptor.id}</code>
                  </td>
                  <td data-label={columns.provider} className="model-catalog-col-extra">
                    {descriptor.providerKey}
                  </td>
                  <td data-label={columns.status}>
                    <StatusPill
                      tone={descriptorTone(descriptor.status)}
                      label={t(`modelCatalog.statuses.${descriptor.status}`)}
                    />
                  </td>
                  <td data-label={columns.modalities} className="model-catalog-col-extra">
                    {descriptor.inputModalities
                      .map((modality) => t(`modelCatalog.modalities.${modality}`))
                      .join(', ')}
                  </td>
                  <td data-label={columns.limits} className="model-catalog-col-extra">
                    {t('modelCatalog.descriptors.limitsValue', {
                      window: formatNumber(descriptor.limits.context, locale),
                      output: formatNumber(descriptor.limits.output, locale),
                    })}
                  </td>
                  <td data-label={columns.thinking} className="model-catalog-col-extra">
                    {thinkingText(descriptor, knob)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableRegion>
      )}
    </section>
  );
}
