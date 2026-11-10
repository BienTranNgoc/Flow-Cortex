import { ApiError, type AiModel, type ProbeResult } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pager } from '../../app/AdminList';
import { EmptyState } from '../../app/EmptyState';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { ModelForm } from './ModelForm';
import { ProbeOutcome } from './ProbeOutcome';
import {
  useAiModels,
  useDeleteModelMutation,
  useReplaceModelMutation,
  useTestModelMutation,
} from './useAiProviders';

type DialogState =
  | { readonly kind: 'add' }
  | { readonly kind: 'edit'; readonly entry: AiModel }
  | { readonly kind: 'delete'; readonly entry: AiModel };

type TestOutcome = ProbeResult | 'failed';

interface ModelsTabProps {
  readonly canUpdate: boolean;
  readonly providerKeys: readonly string[];
}

function DeleteDialog({
  entry,
  onDeleted,
  onClose,
}: Readonly<{ entry: AiModel; onDeleted: () => void; onClose: () => void }>) {
  const { t } = useTranslation();
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const remove = useDeleteModelMutation();
  const problem = remove.error instanceof ApiError ? remove.error.problem : undefined;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog providers-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}
    >
      <div className="providers-dialog__body">
        <h2 id={titleId}>{t('providers.delete.title', { name: entry.displayName })}</h2>
        <p>{t('providers.delete.description', { modelKey: entry.modelKey })}</p>
        {remove.isError && (
          <p className="cortex-admin-form-error" role="alert">
            {problem?.detail ?? t('providers.delete.error')}
          </p>
        )}
        <div className="providers-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('providers.delete.cancel')}
          </button>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--danger"
            disabled={remove.isPending}
            onClick={() => remove.mutate(entry.modelKey, { onSuccess: onDeleted })}
          >
            {remove.isPending ? t('providers.delete.working') : t('providers.delete.confirm')}
          </button>
        </div>
      </div>
    </dialog>
  );
}

interface ModelRowProps {
  readonly entry: AiModel;
  readonly canUpdate: boolean;
  readonly columns: Record<string, string>;
  readonly onEdit: (entry: AiModel) => void;
  readonly onDelete: (entry: AiModel) => void;
  readonly onToggleStart: () => void;
  readonly onToggleFailed: (entry: AiModel) => void;
}

/** One model row; it owns its test and active-switch mutations so concurrent rows never share state. */
function ModelRow({
  entry,
  canUpdate,
  columns,
  onEdit,
  onDelete,
  onToggleStart,
  onToggleFailed,
}: Readonly<ModelRowProps>) {
  const { t } = useTranslation();
  const toggle = useReplaceModelMutation();
  const test = useTestModelMutation();
  const [outcome, setOutcome] = useState<TestOutcome | undefined>();
  const yesNo = (value: boolean) => t(value ? 'providers.models.yes' : 'providers.models.no');

  const flipActive = () => {
    onToggleStart();
    toggle.mutate(
      {
        modelKey: entry.modelKey,
        providerKey: entry.providerKey,
        displayName: entry.displayName,
        maxOutputTokens: entry.maxOutputTokens,
        displayOrder: entry.displayOrder,
        supportsVision: entry.supportsVision,
        supportsAudio: entry.supportsAudio,
        active: !entry.active,
      },
      { onError: () => onToggleFailed(entry) },
    );
  };

  const runTest = () => {
    test.mutate(entry.modelKey, {
      onSuccess: setOutcome,
      onError: () => setOutcome('failed'),
    });
  };

  return (
    <tr>
      <td data-label={columns.modelKey}>
        <code className="cortex-admin-table-job-id">{entry.modelKey}</code>
      </td>
      <td data-label={columns.provider} className="providers-col-extra">
        {entry.providerKey}
      </td>
      <td data-label={columns.displayName} className="providers-col-extra">
        {entry.displayName}
      </td>
      <td data-label={columns.tokens} className="providers-col-extra">
        {entry.maxOutputTokens}
      </td>
      <td data-label={columns.vision} className="providers-col-extra">
        {yesNo(entry.supportsVision)}
      </td>
      <td data-label={columns.audio} className="providers-col-extra">
        {yesNo(entry.supportsAudio)}
      </td>
      <td data-label={columns.active}>
        {canUpdate ? (
          <label className="providers-switch">
            <input
              type="checkbox"
              role="switch"
              checked={entry.active}
              disabled={toggle.isPending}
              aria-label={t('providers.models.activeSwitch', { name: entry.displayName })}
              onChange={flipActive}
            />
          </label>
        ) : (
          <StatusPill
            tone={entry.active ? 'success' : 'muted'}
            label={t(
              entry.active ? 'providers.models.activeState' : 'providers.models.inactiveState',
            )}
          />
        )}
      </td>
      <td data-label={columns.order} className="providers-col-extra">
        {entry.displayOrder}
      </td>
      {canUpdate && (
        <td data-label={columns.actions}>
          <div className="cortex-admin-row-actions">
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm"
              aria-label={t('providers.models.editFor', { name: entry.displayName })}
              onClick={() => onEdit(entry)}
            >
              {t('providers.models.edit')}
            </button>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm"
              aria-label={t('providers.models.testFor', { name: entry.displayName })}
              disabled={test.isPending}
              onClick={runTest}
            >
              {test.isPending ? t('providers.models.testing') : t('providers.models.test')}
            </button>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm cortex-admin-btn--danger"
              aria-label={t('providers.models.deleteFor', { name: entry.displayName })}
              onClick={() => onDelete(entry)}
            >
              {t('providers.models.delete')}
            </button>
          </div>
          <ProbeOutcome
            result={outcome === 'failed' ? undefined : outcome}
            failed={outcome === 'failed'}
          />
        </td>
      )}
    </tr>
  );
}

/** Paged table of registered models with the add, edit, delete, active-switch and test controls. */
export function ModelsTab({ canUpdate, providerKeys }: ModelsTabProps) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [notice, setNotice] = useState('');
  const query = useAiModels(page);
  const data = query.data;

  const columns = {
    modelKey: t('providers.models.columns.modelKey'),
    provider: t('providers.models.columns.provider'),
    displayName: t('providers.models.columns.displayName'),
    tokens: t('providers.models.columns.tokens'),
    vision: t('providers.models.columns.vision'),
    audio: t('providers.models.columns.audio'),
    active: t('providers.models.columns.active'),
    order: t('providers.models.columns.order'),
    actions: t('providers.models.columns.actions'),
  };

  const open = (next: DialogState) => {
    setNotice('');
    setDialog(next);
  };

  return (
    <section className="providers-tab" aria-label={t('providers.tabs.models')}>
      <div className="cortex-admin-toolbar">
        <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
          {t('providers.models.refresh')}
        </button>
        {canUpdate && (
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--primary"
            onClick={() => open({ kind: 'add' })}
          >
            {t('providers.models.add')}
          </button>
        )}
      </div>

      {notice && (
        <p role="status" className="cortex-admin-form-success">
          {notice}
        </p>
      )}

      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">{t('providers.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('providers.retry')}
          </button>
        </div>
      )}
      {(query.isPending || query.isPlaceholderData) && (
        <output className="cortex-admin-loading">{t('providers.loading')}</output>
      )}
      {data?.items.length === 0 && (
        <EmptyState
          title={t('providers.models.empty')}
          {...(canUpdate ? { description: t('providers.models.emptyHint') } : {})}
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
        <TableRegion label={t('providers.models.tableLabel')} busy={query.isPlaceholderData}>
          <table className="cortex-admin-table providers-table">
            <thead>
              <tr>
                <th scope="col">{columns.modelKey}</th>
                <th scope="col" className="providers-col-extra">
                  {columns.provider}
                </th>
                <th scope="col" className="providers-col-extra">
                  {columns.displayName}
                </th>
                <th scope="col" className="providers-col-extra">
                  {columns.tokens}
                </th>
                <th scope="col" className="providers-col-extra">
                  {columns.vision}
                </th>
                <th scope="col" className="providers-col-extra">
                  {columns.audio}
                </th>
                <th scope="col">{columns.active}</th>
                <th scope="col" className="providers-col-extra">
                  {columns.order}
                </th>
                {canUpdate && <th scope="col">{columns.actions}</th>}
              </tr>
            </thead>
            <tbody>
              {data.items.map((entry) => (
                <ModelRow
                  key={entry.modelKey}
                  entry={entry}
                  canUpdate={canUpdate}
                  columns={columns}
                  onEdit={(next) => open({ kind: 'edit', entry: next })}
                  onDelete={(next) => open({ kind: 'delete', entry: next })}
                  onToggleStart={() => setNotice('')}
                  onToggleFailed={(failed) =>
                    setNotice(t('providers.models.toggleFailed', { name: failed.displayName }))
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
          nounKey="providers.models.noun"
          onPageChange={setPage}
        />
      )}

      {dialog?.kind === 'delete' && (
        <DeleteDialog
          entry={dialog.entry}
          onDeleted={() => {
            setNotice(t('providers.models.deleted', { name: dialog.entry.displayName }));
            setDialog(null);
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {(dialog?.kind === 'add' || dialog?.kind === 'edit') && (
        <ModelForm
          key={dialog.kind === 'edit' ? dialog.entry.modelKey : 'add'}
          entry={dialog.kind === 'edit' ? dialog.entry : undefined}
          providerKeys={providerKeys}
          onSaved={(saved, created) => {
            setNotice(
              t(created ? 'providers.models.created' : 'providers.models.saved', {
                name: saved.displayName,
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
