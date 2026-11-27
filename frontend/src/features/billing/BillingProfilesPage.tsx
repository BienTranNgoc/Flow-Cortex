import type { BillingProfileResponse } from '@flow-cortex/api-client';
import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { useModalDialog } from '../../lib/useModalDialog';
import { ConfirmDialog } from '../drive/shared/ConfirmDialog';
import '../drive/shared/DriveShared.css';
import { BillingProfileForm } from './BillingProfileForm';
import {
  isGone,
  MAX_PROFILES,
  useBillingProfiles,
  useDeleteBillingProfile,
  useSetDefaultBillingProfile,
} from './useBillingProfiles';
import './billing.css';

type Editor = { readonly profile?: BillingProfileResponse } | null;

/** The member's invoice details: add, edit, choose the default and delete. */
export function BillingProfilesPage() {
  const { t } = useTranslation();
  const titleId = useId();
  const limitId = useId();
  const list = useBillingProfiles();
  const setDefault = useSetDefaultBillingProfile();
  const remove = useDeleteBillingProfile();
  const [editor, setEditor] = useState<Editor>(null);
  const [deleting, setDeleting] = useState<BillingProfileResponse | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  const addRef = useRef<HTMLButtonElement>(null);
  const firstFieldRef = useRef<HTMLSelectElement>(null);
  const dialogRef = useModalDialog(editor !== null, firstFieldRef);

  const profiles = list.data ?? [];
  const closeEditor = () => setEditor(null);

  const makeDefault = (profile: BillingProfileResponse) => {
    setRowError(null);
    setDefault.mutate(profile.id, {
      onError: (error) =>
        setRowError(t(isGone(error) ? 'billing.profiles.error.notFound' : 'billing.profiles.error.setDefault')),
    });
  };

  const confirmDelete = () => {
    if (!deleting) return;
    setDeleteError(null);
    remove.mutate(deleting.id, {
      onSuccess: () => {
        setDeleting(null);
        addRef.current?.focus();
      },
      onError: (error) => {
        if (isGone(error)) {
          setDeleting(null);
          setRowError(t('billing.profiles.error.notFound'));
        } else {
          setDeleteError(t('billing.profiles.error.delete'));
        }
      },
    });
  };

  const atLimit = profiles.length >= MAX_PROFILES;
  const addButton = (
    <button
      ref={addRef}
      type="button"
      className="drive-button drive-button--primary"
      aria-disabled={atLimit || undefined}
      aria-describedby={atLimit ? limitId : undefined}
      onClick={() => {
        if (!atLimit) setEditor({});
      }}
    >
      {t('billing.profiles.add')}
    </button>
  );

  return (
    <div className="billing-page">
      <PageHeading
        title={t('billing.profiles.title')}
        lede={t('billing.profiles.lede')}
        actions={profiles.length > 0 ? addButton : undefined}
      />

      <section
        aria-busy={list.isPending || list.isFetching}
        aria-label={t('billing.profiles.listLabel')}
        className="billing-list"
      >
        {list.isPending && <Skeleton shape="card" count={2} />}
        {list.isError && (
          <StatusBlock
            kind="error"
            message={t('billing.profiles.loadError')}
            action={{ label: t('billing.profiles.retry'), onClick: () => void list.refetch() }}
          />
        )}
        {rowError && (
          <p role="alert" className="billing-form__failure">
            {rowError}
          </p>
        )}
        {atLimit && (
          <p id={limitId} className="billing-field__hint">
            {t('billing.profiles.limitReached', { max: MAX_PROFILES })}
          </p>
        )}
        {list.isSuccess && profiles.length === 0 && (
          <div className="status-block">
            <p className="status-block__message">{t('billing.profiles.empty')}</p>
            {addButton}
          </div>
        )}
        {list.isSuccess && profiles.length > 0 && (
          <ul className="billing-profiles">
            {profiles.map((profile) => (
              <li key={profile.id} className="billing-profile">
                <div className="billing-profile__head">
                  <h2 className="billing-profile__name">{profile.legalName}</h2>
                  <span className="billing-chip">{t(`billing.profiles.type.${profile.type}`)}</span>
                  {profile.isDefault && (
                    <span className="billing-chip billing-chip--succeeded">
                      {t('billing.profiles.default')}
                    </span>
                  )}
                </div>
                <dl className="billing-profile__facts">
                  {profile.taxCode && (
                    <div>
                      <dt>{t('billing.profiles.field.taxCode')}</dt>
                      <dd>{profile.taxCode}</dd>
                    </div>
                  )}
                  {profile.personalIdNumber && (
                    <div>
                      <dt>{t('billing.profiles.field.personalIdNumber')}</dt>
                      <dd>{profile.personalIdNumber}</dd>
                    </div>
                  )}
                  <div>
                    <dt>{t('billing.profiles.field.address')}</dt>
                    <dd>{profile.address}</dd>
                  </div>
                  {profile.email && (
                    <div>
                      <dt>{t('billing.profiles.field.email')}</dt>
                      <dd>{profile.email}</dd>
                    </div>
                  )}
                </dl>
                <div className="billing-profile__actions">
                  <button type="button" className="drive-button" onClick={() => setEditor({ profile })}>
                    {t('billing.profiles.action.edit')}
                  </button>
                  {!profile.isDefault && (
                    <button
                      type="button"
                      className="drive-button"
                      aria-disabled={setDefault.isPending || undefined}
                      onClick={() => {
                        if (!setDefault.isPending) makeDefault(profile);
                      }}
                    >
                      {t('billing.profiles.action.setDefault')}
                    </button>
                  )}
                  <button
                    type="button"
                    className="drive-button drive-button--danger"
                    onClick={() => {
                      setDeleteError(null);
                      setDeleting(profile);
                    }}
                  >
                    {t('billing.profiles.action.delete')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <dialog
        ref={dialogRef}
        className="drive-dialog billing-dialog"
        aria-labelledby={titleId}
        onClose={() => {
          if (editor) closeEditor();
        }}
      >
        {editor && (
          <>
            <h2 id={titleId} className="drive-dialog__title">
              {t(editor.profile ? 'billing.profiles.dialog.editTitle' : 'billing.profiles.dialog.createTitle')}
            </h2>
            <BillingProfileForm
              key={editor.profile?.id ?? 'new'}
              {...(editor.profile ? { profile: editor.profile } : {})}
              firstFieldRef={firstFieldRef}
              onSaved={closeEditor}
              onCancel={closeEditor}
            />
          </>
        )}
      </dialog>

      <ConfirmDialog
        open={deleting !== null}
        title={t('billing.profiles.confirmDelete.title')}
        message={t('billing.profiles.confirmDelete.message', { name: deleting?.legalName ?? '' })}
        confirmLabel={t('billing.profiles.confirmDelete.confirm')}
        busyLabel={t('billing.profiles.confirmDelete.busy')}
        error={deleteError}
        busy={remove.isPending}
        onCancel={() => setDeleting(null)}
        onConfirm={confirmDelete}
      />
    </div>
  );
}
