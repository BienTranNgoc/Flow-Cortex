import { ApiError, type CatalogCapability, type CatalogModel } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { Pager } from '../../app/AdminList';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { CATEGORIES, ICON_PATTERN, UI_GROUPS, descriptorTone, labelPair } from './catalogOptions';
import { ModelRowDialog } from './ModelRowDialog';
import {
  useCapability,
  useCapabilityModels,
  useDeleteCatalogModelMutation,
  useReplaceCapabilityMutation,
  useReplaceCatalogModelMutation,
} from './useModelCatalog';
import './modelCatalog.css';

type DialogState =
  | { readonly kind: 'add' }
  | { readonly kind: 'edit'; readonly entry: CatalogModel }
  | { readonly kind: 'delete'; readonly entry: CatalogModel };

type DisplayField = 'category' | 'uiGroup' | 'uiIcon';
type DisplayErrors = Partial<Record<DisplayField, string | undefined>>;
const DISPLAY_FIELDS: readonly DisplayField[] = ['category', 'uiGroup', 'uiIcon'];

function DisplayFieldsForm({
  capability,
  row,
  canUpdate,
}: Readonly<{ capability: string; row: CatalogCapability; canUpdate: boolean }>) {
  const { t } = useTranslation();
  const baseId = useId();
  const replace = useReplaceCapabilityMutation(capability);
  const [category, setCategory] = useState(row.category);
  const [uiGroup, setUiGroup] = useState(row.uiGroup);
  const [uiIcon, setUiIcon] = useState(row.uiIcon ?? '');
  const [clientErrors, setClientErrors] = useState<DisplayErrors>({});
  const [saved, setSaved] = useState(false);

  const categoryLabel = (value: string) =>
    t(`modelCatalog.categories.${value}`, { defaultValue: value });
  const groupLabel = (value: string) => t(`modelCatalog.groups.${value}`, { defaultValue: value });

  if (!canUpdate) {
    return (
      <dl className="model-catalog-facts" aria-label={t('modelCatalog.capability.displayFields')}>
        <div>
          <dt>{t('modelCatalog.capability.category')}</dt>
          <dd>{categoryLabel(row.category)}</dd>
        </div>
        <div>
          <dt>{t('modelCatalog.capability.group')}</dt>
          <dd>{groupLabel(row.uiGroup)}</dd>
        </div>
        <div>
          <dt>{t('modelCatalog.capability.icon')}</dt>
          <dd>{row.uiIcon ? <code>{row.uiIcon}</code> : t('modelCatalog.capability.iconNone')}</dd>
        </div>
      </dl>
    );
  }

  const serverErrors: DisplayErrors = {};
  if (replace.error instanceof ApiError && replace.error.problem?.errors) {
    for (const name of DISPLAY_FIELDS) {
      const message = replace.error.problem.errors[name];
      if (message) serverErrors[name] = message;
    }
  }
  const problem = replace.error instanceof ApiError ? replace.error.problem : undefined;
  const fieldErrors: DisplayErrors = { ...serverErrors, ...clientErrors };
  const formError =
    replace.error && Object.keys(serverErrors).length === 0
      ? (problem?.detail ?? t('modelCatalog.capability.saveError'))
      : undefined;

  const touch = (name: DisplayField) => {
    setSaved(false);
    replace.reset();
    setClientErrors((current) => ({ ...current, [name]: undefined }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (replace.isPending) return;
    const icon = uiIcon.trim();
    const errors: DisplayErrors =
      icon !== '' && !ICON_PATTERN.test(icon)
        ? { uiIcon: t('modelCatalog.capability.errors.icon') }
        : {};
    setClientErrors(errors);
    if (errors.uiIcon) return;
    replace.mutate(
      { category, uiGroup, ...(icon === '' ? {} : { uiIcon: icon }) },
      { onSuccess: () => setSaved(true) },
    );
  };

  const fieldProps = (name: DisplayField) => ({
    id: `${baseId}-${name}`,
    'aria-invalid': fieldErrors[name] ? true : undefined,
    'aria-describedby': fieldErrors[name] ? `${baseId}-${name}-error` : undefined,
  });
  const fieldError = (name: DisplayField) =>
    fieldErrors[name] ? (
      <p id={`${baseId}-${name}-error`} className="cortex-admin-form-error">
        {fieldErrors[name]}
      </p>
    ) : null;

  return (
    <form className="model-catalog-display-form" onSubmit={submit} noValidate>
      <div className="cortex-admin-field">
        <label htmlFor={`${baseId}-category`}>{t('modelCatalog.capability.category')}</label>
        <select
          {...fieldProps('category')}
          value={category}
          onChange={(event) => {
            setCategory(event.target.value);
            touch('category');
          }}
        >
          {!CATEGORIES.some((value) => value === category) && (
            <option value={category}>{category}</option>
          )}
          {CATEGORIES.map((value) => (
            <option key={value} value={value}>
              {categoryLabel(value)}
            </option>
          ))}
        </select>
        {fieldError('category')}
      </div>
      <div className="cortex-admin-field">
        <label htmlFor={`${baseId}-uiGroup`}>{t('modelCatalog.capability.group')}</label>
        <select
          {...fieldProps('uiGroup')}
          value={uiGroup}
          onChange={(event) => {
            setUiGroup(event.target.value);
            touch('uiGroup');
          }}
        >
          {!UI_GROUPS.some((value) => value === uiGroup) && (
            <option value={uiGroup}>{uiGroup}</option>
          )}
          {UI_GROUPS.map((value) => (
            <option key={value} value={value}>
              {groupLabel(value)}
            </option>
          ))}
        </select>
        {fieldError('uiGroup')}
      </div>
      <div className="cortex-admin-field">
        <label htmlFor={`${baseId}-uiIcon`}>{t('modelCatalog.capability.icon')}</label>
        <input
          {...fieldProps('uiIcon')}
          value={uiIcon}
          maxLength={64}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => {
            setUiIcon(event.target.value);
            touch('uiIcon');
          }}
        />
        <small className="model-catalog-hint">{t('modelCatalog.capability.iconHint')}</small>
        {fieldError('uiIcon')}
      </div>
      <div className="model-catalog-display-form__actions">
        <button
          type="submit"
          className="cortex-admin-btn cortex-admin-btn--primary"
          disabled={replace.isPending}
        >
          {replace.isPending
            ? t('modelCatalog.capability.saving')
            : t('modelCatalog.capability.save')}
        </button>
        {saved && (
          <p role="status" className="cortex-admin-form-success">
            {t('modelCatalog.capability.saved')}
          </p>
        )}
      </div>
      {formError && (
        <p className="cortex-admin-form-error" role="alert">
          {formError}
        </p>
      )}
    </form>
  );
}

function DeleteDialog({
  capability,
  entry,
  onDeleted,
  onClose,
}: Readonly<{
  capability: string;
  entry: CatalogModel;
  onDeleted: () => void;
  onClose: () => void;
}>) {
  const { t } = useTranslation();
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const remove = useDeleteCatalogModelMutation(capability);
  const problem = remove.error instanceof ApiError ? remove.error.problem : undefined;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog model-catalog-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
    >
      <div className="model-catalog-dialog__body">
        <h2 id={titleId}>{t('modelCatalog.delete.title', { name: entry.modelKey })}</h2>
        <p>{t('modelCatalog.delete.description', { capability })}</p>
        {remove.isError && (
          <p className="cortex-admin-form-error" role="alert">
            {problem?.detail ?? t('modelCatalog.delete.error')}
          </p>
        )}
        <div className="model-catalog-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('modelCatalog.delete.cancel')}
          </button>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--danger"
            disabled={remove.isPending}
            onClick={() => remove.mutate(entry.modelKey, { onSuccess: onDeleted })}
          >
            {remove.isPending ? t('modelCatalog.delete.working') : t('modelCatalog.delete.confirm')}
          </button>
        </div>
      </div>
    </dialog>
  );
}

interface ModelRowProps {
  readonly capability: string;
  readonly entry: CatalogModel;
  readonly canUpdate: boolean;
  readonly columns: Record<string, string>;
  readonly onEdit: (entry: CatalogModel) => void;
  readonly onDelete: (entry: CatalogModel) => void;
  readonly onToggleStart: () => void;
  readonly onToggleFailed: (entry: CatalogModel, detail: string | undefined) => void;
}

/** One model row; it owns its enabled-switch mutation so concurrent rows never share state. */
function ModelRow({
  capability,
  entry,
  canUpdate,
  columns,
  onEdit,
  onDelete,
  onToggleStart,
  onToggleFailed,
}: Readonly<ModelRowProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const toggle = useReplaceCatalogModelMutation(capability);
  const descriptor = entry.descriptor ?? undefined;
  const labels = labelPair(entry);

  const flipEnabled = () => {
    onToggleStart();
    toggle.mutate(
      {
        modelKey: entry.modelKey,
        body: {
          providerKey: entry.providerKey,
          mode: entry.mode,
          enabled: !entry.enabled,
          notImplemented: entry.notImplemented,
          ...(entry.vendorLabel ? { vendorLabel: entry.vendorLabel } : {}),
          ...(entry.makerLabel ? { makerLabel: entry.makerLabel } : {}),
        },
      },
      {
        onError: (error) =>
          onToggleFailed(entry, error instanceof ApiError ? error.problem?.detail : undefined),
      },
    );
  };

  return (
    <tr>
      <td data-label={columns.modelKey}>
        <code className="cortex-admin-table-job-id">{entry.modelKey}</code>
        {entry.notImplemented && (
          <StatusPill tone="warning" label={t('modelCatalog.models.notImplemented')} />
        )}
      </td>
      <td data-label={columns.provider} className="model-catalog-col-extra">
        {entry.providerKey}
        {labels !== '' && <span className="model-catalog-sub">{labels}</span>}
      </td>
      <td data-label={columns.mode} className="model-catalog-col-extra">
        {t(`modelCatalog.modes.${entry.mode}`, { defaultValue: entry.mode })}
      </td>
      <td data-label={columns.descriptor} className="model-catalog-col-extra">
        {entry.mode !== 'text' ? (
          '—'
        ) : descriptor ? (
          <span className="model-catalog-descriptor">
            <StatusPill
              tone={descriptorTone(descriptor.status)}
              label={t(`modelCatalog.statuses.${descriptor.status}`)}
            />
            <span>
              {t('modelCatalog.descriptors.limitsValue', {
                window: formatNumber(descriptor.limits.context, locale),
                output: formatNumber(descriptor.limits.output, locale),
              })}
            </span>
          </span>
        ) : (
          <StatusPill tone="muted" label={t('modelCatalog.models.noDescriptor')} />
        )}
      </td>
      <td data-label={columns.enabled}>
        {canUpdate ? (
          <label className="model-catalog-switch">
            <input
              type="checkbox"
              role="switch"
              checked={entry.enabled}
              disabled={toggle.isPending}
              aria-label={t('modelCatalog.models.enabledSwitch', { name: entry.modelKey })}
              onChange={flipEnabled}
            />
          </label>
        ) : (
          <StatusPill
            tone={entry.enabled ? 'success' : 'muted'}
            label={t(
              entry.enabled
                ? 'modelCatalog.models.enabledState'
                : 'modelCatalog.models.disabledState',
            )}
          />
        )}
      </td>
      {canUpdate && (
        <td data-label={columns.actions}>
          <div className="cortex-admin-row-actions">
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm"
              aria-label={t('modelCatalog.models.editFor', { name: entry.modelKey })}
              onClick={() => onEdit(entry)}
            >
              {t('modelCatalog.models.edit')}
            </button>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm cortex-admin-btn--danger"
              aria-label={t('modelCatalog.models.deleteFor', { name: entry.modelKey })}
              onClick={() => onDelete(entry)}
            >
              {t('modelCatalog.models.delete')}
            </button>
          </div>
        </td>
      )}
    </tr>
  );
}

function ModelsSection({
  capability,
  canUpdate,
}: Readonly<{ capability: string; canUpdate: boolean }>) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [notice, setNotice] = useState('');
  const [failure, setFailure] = useState('');
  const query = useCapabilityModels(capability, page);
  const data = query.data;

  const columns = {
    modelKey: t('modelCatalog.models.columns.modelKey'),
    provider: t('modelCatalog.models.columns.provider'),
    mode: t('modelCatalog.models.columns.mode'),
    descriptor: t('modelCatalog.models.columns.descriptor'),
    enabled: t('modelCatalog.models.columns.enabled'),
    actions: t('modelCatalog.models.columns.actions'),
  };

  const open = (next: DialogState) => {
    setNotice('');
    setFailure('');
    setDialog(next);
  };

  return (
    <section className="model-catalog-tab" aria-label={t('modelCatalog.models.title')}>
      <div className="model-catalog-section-head">
        <h2>{t('modelCatalog.models.title')}</h2>
        <div className="cortex-admin-toolbar">
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('modelCatalog.models.refresh')}
          </button>
          {canUpdate && (
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--primary"
              onClick={() => open({ kind: 'add' })}
            >
              {t('modelCatalog.models.add')}
            </button>
          )}
        </div>
      </div>

      {notice && (
        <p role="status" className="cortex-admin-form-success">
          {notice}
        </p>
      )}
      {failure && (
        <p role="alert" className="cortex-admin-form-error">
          {failure}
        </p>
      )}

      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">{t('modelCatalog.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('modelCatalog.retry')}
          </button>
        </div>
      )}
      {(query.isPending || query.isPlaceholderData) && (
        <output className="cortex-admin-loading">{t('modelCatalog.loading')}</output>
      )}
      {data?.items.length === 0 && (
        <EmptyState
          title={t('modelCatalog.models.empty')}
          {...(canUpdate ? { description: t('modelCatalog.models.emptyHint') } : {})}
          action={
            data.page > 0 ? (
              <button type="button" className="cortex-admin-btn" onClick={() => setPage(0)}>
                {t('common.shell.list.backToFirstPage')}
              </button>
            ) : undefined
          }
        />
      )}
      {data && data.items.length > 0 && (
        <TableRegion label={t('modelCatalog.models.tableLabel')} busy={query.isPlaceholderData}>
          <table className="cortex-admin-table model-catalog-table">
            <thead>
              <tr>
                <th scope="col">{columns.modelKey}</th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.provider}
                </th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.mode}
                </th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.descriptor}
                </th>
                <th scope="col">{columns.enabled}</th>
                {canUpdate && <th scope="col">{columns.actions}</th>}
              </tr>
            </thead>
            <tbody>
              {data.items.map((entry) => (
                <ModelRow
                  key={entry.modelKey}
                  capability={capability}
                  entry={entry}
                  canUpdate={canUpdate}
                  columns={columns}
                  onEdit={(next) => open({ kind: 'edit', entry: next })}
                  onDelete={(next) => open({ kind: 'delete', entry: next })}
                  onToggleStart={() => {
                    setNotice('');
                    setFailure('');
                  }}
                  onToggleFailed={(failed, detail) =>
                    setFailure(
                      detail ?? t('modelCatalog.models.toggleFailed', { name: failed.modelKey }),
                    )
                  }
                />
              ))}
            </tbody>
          </table>
        </TableRegion>
      )}
      {data && (data.items.length > 0 || data.page > 0) && (
        <Pager
          page={data.page}
          size={data.size}
          total={data.total}
          nounKey="modelCatalog.models.noun"
          onPageChange={setPage}
        />
      )}

      {dialog?.kind === 'delete' && (
        <DeleteDialog
          capability={capability}
          entry={dialog.entry}
          onDeleted={() => {
            setNotice(t('modelCatalog.models.deleted', { name: dialog.entry.modelKey }));
            setDialog(null);
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {(dialog?.kind === 'add' || dialog?.kind === 'edit') && (
        <ModelRowDialog
          key={dialog.kind === 'edit' ? dialog.entry.modelKey : 'add'}
          capability={capability}
          entry={dialog.kind === 'edit' ? dialog.entry : undefined}
          onSaved={(saved, created) => {
            setNotice(
              t(created ? 'modelCatalog.models.created' : 'modelCatalog.models.saved', {
                name: saved.modelKey,
              }),
            );
            setDialog(null);
          }}
          onClose={() => setDialog(null)}
        />
      )}
    </section>
  );
}

/** `/model-catalog/:capability`: the capability's display fields and its model rows. */
export function CapabilityPage() {
  const { t } = useTranslation();
  const { capability = '' } = useParams();
  usePageTitle(capability);
  const canUpdate = can(useMeQuery().data, 'ai.model.update');
  const query = useCapability(capability);
  const row = query.data;
  const notFound = query.error instanceof ApiError && query.error.status === 404;

  return (
    <div className="model-catalog-page">
      <PageHeading
        eyebrow={t('modelCatalog.eyebrow')}
        title={capability}
        description={t('modelCatalog.capability.description')}
        actions={
          <Link className="cortex-admin-btn" to="/model-catalog">
            {t('modelCatalog.capability.back')}
          </Link>
        }
      />
      {query.isPending && (
        <output className="cortex-admin-loading">{t('modelCatalog.loading')}</output>
      )}
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">
            {notFound ? t('modelCatalog.capability.notFound') : t('modelCatalog.loadError')}
          </p>
          {!notFound && (
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              {t('modelCatalog.retry')}
            </button>
          )}
        </div>
      )}
      {row && (
        <>
          <p className="model-catalog-note">{t('modelCatalog.capability.routingNote')}</p>
          {!row.registered && (
            <p className="cortex-admin-disabled-banner" role="status">
              {t('modelCatalog.capability.notRegistered')}
            </p>
          )}
          <DisplayFieldsForm
            key={row.capability}
            capability={capability}
            row={row}
            canUpdate={canUpdate}
          />
          <ModelsSection capability={capability} canUpdate={canUpdate} />
        </>
      )}
    </div>
  );
}
