import { ApiError, type PermissionCatalogEntry, type RoleResponse } from '@flow-cortex/api-client';
import { ArrowLeft } from '@phosphor-icons/react';
import { useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { DeleteRoleDialog } from './DeleteRoleDialog';
import { missingViewFeatures } from './permissionSet';
import { PermissionMatrix } from './PermissionMatrix';
import { roleDisplayName } from './roleDisplay';
import {
  ROLE_CODE_PATTERN,
  useCreateRoleMutation,
  useDeleteRoleMutation,
  usePermissionCatalogQuery,
  useRenameRoleMutation,
  useReplacePermissionsMutation,
  useRoleQuery,
} from './useRoles';
import { useLeaveGuard } from './useLeaveGuard';
import './roles.css';

const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;

interface Baseline {
  readonly name: string;
  readonly description: string;
  readonly keys: ReadonlySet<string>;
  readonly version: number;
}

type Notice =
  | { readonly kind: 'created'; readonly name: string }
  | { readonly kind: 'saved'; readonly name: string; readonly count: number }
  | { readonly kind: 'renamed'; readonly name: string };

interface SaveProblem {
  readonly kind: 'stale' | 'refused' | 'error';
  readonly message?: string;
  readonly entries?: readonly (readonly [string, string])[];
}

interface FieldErrors {
  readonly name?: string;
  readonly description?: string;
}

function sameKeys(left: ReadonlySet<string>, right: ReadonlySet<string>): boolean {
  return left.size === right.size && [...left].every((key) => right.has(key));
}

function baselineOf(role: RoleResponse | undefined): Baseline {
  return {
    name: role?.name ?? '',
    description: role?.description ?? '',
    keys: new Set<string>(role?.permissions ?? []),
    version: role?.permissionsVersion ?? 0,
  };
}

function noticeFromState(state: unknown): Notice | null {
  const candidate = (state as { notice?: Notice } | null)?.notice;
  return candidate?.kind === 'created' ? candidate : null;
}

export function RoleDetailPage() {
  const { t } = useTranslation();
  const { code } = useParams();
  const isNew = code === undefined;
  const validCode = isNew || ROLE_CODE_PATTERN.test(code);
  const catalogQuery = usePermissionCatalogQuery();
  const roleQuery = useRoleQuery(code ?? '');
  const [reloadToken, setReloadToken] = useState(0);
  usePageTitle(isNew ? t('roles.detail.titleNew') : t('roles.title'));

  const back = (
    <Link className="cortex-admin-link" to="/roles">
      <ArrowLeft aria-hidden="true" /> {t('roles.detail.backToRoles')}
    </Link>
  );

  if (!validCode) {
    return (
      <div className="roles-page">
        <h1>{t('roles.detail.notFound')}</h1>
        <p className="cortex-admin-error" role="alert">
          {t('roles.detail.invalidCode')}
        </p>
        {back}
      </div>
    );
  }

  if (catalogQuery.isError || roleQuery.isError) {
    const missing =
      !catalogQuery.isError &&
      roleQuery.error instanceof ApiError &&
      roleQuery.error.status === 404;
    return (
      <div className="roles-page">
        <h1>{missing ? t('roles.detail.notFound') : t('roles.title')}</h1>
        <div className="cortex-admin-error">
          <p role="alert">
            {missing && roleQuery.error instanceof ApiError
              ? (roleQuery.error.problem?.detail ?? t('roles.detail.notFound'))
              : t('roles.detail.errors.load')}
          </p>
          {!missing && (
            <button
              type="button"
              className="cortex-admin-btn"
              onClick={() => (catalogQuery.isError ? catalogQuery.refetch() : roleQuery.refetch())}
            >
              {t('roles.detail.retry')}
            </button>
          )}
        </div>
        {back}
      </div>
    );
  }

  if (catalogQuery.isPending || (!isNew && roleQuery.isPending)) {
    return (
      <div className="roles-page">
        <h1>{t('roles.title')}</h1>
        <output className="cortex-admin-loading">{t('roles.detail.loading')}</output>
      </div>
    );
  }

  return (
    <RoleEditor
      key={`${code ?? 'new'}:${reloadToken}`}
      role={roleQuery.data}
      catalog={catalogQuery.data}
      back={back}
      onReload={async () => {
        const result = await roleQuery.refetch();
        if (result.data) setReloadToken((token) => token + 1);
      }}
    />
  );
}

interface RoleEditorProps {
  readonly role: RoleResponse | undefined;
  readonly catalog: readonly PermissionCatalogEntry[];
  readonly back: ReactNode;
  readonly onReload: () => Promise<void>;
}

function RoleEditor({ role, catalog, back, onReload }: Readonly<RoleEditorProps>) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const me = useMeQuery().data;
  const canManage = can(me, 'system.role.manage');
  const canSeeAccounts = can(me, 'system.admin_account.view');
  const isNew = role === undefined;
  const nameId = useId();
  const descriptionId = useId();
  const createRole = useCreateRoleMutation();
  const renameRole = useRenameRoleMutation();
  const deleteRole = useDeleteRoleMutation();
  const replacePermissions = useReplacePermissionsMutation();

  const [baseline, setBaseline] = useState<Baseline>(() => baselineOf(role));
  const [name, setName] = useState(() => (role ? roleDisplayName(t, role) : ''));
  const [description, setDescription] = useState(baseline.description);
  const [keys, setKeys] = useState<ReadonlySet<string>>(baseline.keys);
  const [notice, setNotice] = useState<Notice | null>(() => noticeFromState(location.state));
  const [problem, setProblem] = useState<SaveProblem | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [partial, setPartial] = useState(false);
  const [saving, setSaving] = useState(false);
  const [left, setLeft] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<{ message: string; held: boolean } | null>(null);

  const superAdmin = role?.superAdmin ?? false;
  const system = role?.system ?? false;
  const endUser = role !== undefined && !role.staff && !role.superAdmin;
  const keysEditable = canManage && !superAdmin && !endUser;
  const textEditable = canManage && !system;
  const displayName = role ? roleDisplayName(t, role) : t('roles.detail.titleNew');

  const trimmedName = name.trim();
  const trimmedDescription = description.trim();
  const textChanged =
    textEditable && (trimmedName !== baseline.name || trimmedDescription !== baseline.description);
  const keysChanged = keysEditable && !sameKeys(keys, baseline.keys);
  const dirty = isNew
    ? trimmedName !== '' || trimmedDescription !== '' || keys.size > 0
    : textChanged || keysChanged;
  useLeaveGuard(canManage && dirty && !left, t('roles.detail.leaveConfirm'));

  const validate = (): boolean => {
    const errors: { name?: string; description?: string } = {};
    if (textEditable) {
      if (trimmedName === '') errors.name = t('roles.detail.errors.nameRequired');
      else if (trimmedName.length > NAME_MAX)
        errors.name = t('roles.detail.errors.nameTooLong', { max: NAME_MAX });
      if (trimmedDescription.length > DESCRIPTION_MAX)
        errors.description = t('roles.detail.errors.descriptionTooLong', { max: DESCRIPTION_MAX });
    }
    setFieldErrors(errors);
    if (errors.name || errors.description) return false;
    const missing = missingViewFeatures(catalog, keys);
    if (keysEditable && missing.length > 0) {
      setProblem({
        kind: 'error',
        message: t('roles.detail.errors.missingView', { features: missing.join(', ') }),
      });
      return false;
    }
    return true;
  };

  const classify = (error: unknown, stage: 'create' | 'permissions' | 'rename'): SaveProblem => {
    if (!(error instanceof ApiError)) {
      return { kind: 'error', message: t('roles.detail.errors.generic') };
    }
    const { status, problem: body } = error;
    if (stage === 'permissions' && status === 409) {
      return { kind: 'stale' };
    }
    if (status === 422) {
      return {
        kind: 'refused',
        message: body?.detail ?? t('roles.detail.errors.generic'),
        entries: Object.entries(body?.errors ?? {}),
      };
    }
    if (status === 400 && body?.errors) {
      setFieldErrors({
        ...(body.errors.name ? { name: body.errors.name } : {}),
        ...(body.errors.description ? { description: body.errors.description } : {}),
      });
    }
    return { kind: 'error', message: body?.detail ?? t('roles.detail.errors.generic') };
  };

  const save = async () => {
    setNotice(null);
    setProblem(null);
    setPartial(false);
    if (!validate()) return;
    const sortedKeys = [...keys].sort();
    setSaving(true);
    let stage: 'create' | 'permissions' | 'rename' = 'create';
    let permissionsSaved = false;
    try {
      if (isNew) {
        const created = await createRole.mutateAsync({
          name: trimmedName,
          ...(trimmedDescription ? { description: trimmedDescription } : {}),
          permissionKeys: sortedKeys,
        });
        setLeft(true);
        navigate(`/roles/${encodeURIComponent(created.code)}`, {
          replace: true,
          state: { notice: { kind: 'created', name: created.name } satisfies Notice },
        });
        return;
      }
      let latest: RoleResponse | undefined;
      if (keysChanged) {
        stage = 'permissions';
        latest = await replacePermissions.mutateAsync({
          code: role.code,
          request: { keys: sortedKeys, expectedVersion: baseline.version },
        });
        permissionsSaved = true;
        const stored = new Set<string>(latest.permissions);
        setBaseline((current) => ({
          ...current,
          keys: stored,
          version: latest?.permissionsVersion ?? current.version,
        }));
        setKeys(stored);
      }
      if (textChanged) {
        stage = 'rename';
        latest = await renameRole.mutateAsync({
          code: role.code,
          request: { name: trimmedName, description: trimmedDescription },
        });
        setBaseline((current) => ({
          ...current,
          name: latest?.name ?? current.name,
          description: latest?.description ?? '',
        }));
      }
      if (latest) {
        setNotice(
          permissionsSaved
            ? { kind: 'saved', name: displayName, count: latest.permissions.length }
            : { kind: 'renamed', name: latest.name },
        );
      }
    } catch (error) {
      setProblem(classify(error, stage));
      setPartial(stage === 'rename' && permissionsSaved);
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!role) return;
    setDeleteError(null);
    try {
      await deleteRole.mutateAsync(role.code);
      setLeft(true);
      navigate('/roles', { replace: true });
    } catch (error) {
      const status = error instanceof ApiError ? error.status : undefined;
      setDeleteError({
        message:
          error instanceof ApiError && error.problem?.detail
            ? error.problem.detail
            : t('roles.detail.errors.deleteGeneric'),
        held: status === 409,
      });
    }
  };

  const reload = async () => {
    setProblem(null);
    await onReload();
  };

  const noticeText =
    notice &&
    {
      created: () => t('roles.detail.notice.created', { name: notice.name }),
      saved: () =>
        t('roles.detail.notice.saved', {
          name: notice.name,
          count: notice.kind === 'saved' ? notice.count : 0,
        }),
      renamed: () => t('roles.detail.notice.renamed', { name: notice.name }),
    }[notice.kind]();

  const note = superAdmin
    ? t('roles.detail.notes.superAdmin')
    : endUser
      ? t('roles.detail.notes.endUser')
      : !canManage
        ? t('roles.detail.notes.readOnly')
        : system
          ? t('roles.detail.notes.system')
          : undefined;

  return (
    <div className="roles-page roles-detail">
      <PageHeading
        eyebrow={t('roles.title')}
        title={
          isNew ? t('roles.detail.titleNew') : t('roles.detail.titleView', { name: displayName })
        }
        actions={back}
      />
      {role && (
        <p className="roles-detail__badges">
          <StatusPill
            tone="info"
            label={t(system ? 'roles.detail.badges.system' : 'roles.detail.badges.custom')}
          />
          {superAdmin && <StatusPill tone="warning" label={t('roles.detail.badges.superAdmin')} />}
        </p>
      )}
      {note && <p className="roles-detail__note">{note}</p>}
      {noticeText && (
        <p role="status" className="cortex-admin-form-success">
          {noticeText}
        </p>
      )}
      {problem?.kind === 'stale' && (
        <div className="cortex-admin-error roles-detail__banner" role="alert">
          <p>{t('roles.detail.stale')}</p>
          <button type="button" className="cortex-admin-btn" onClick={reload}>
            {t('roles.detail.actions.reload')}
          </button>
        </div>
      )}
      {problem && problem.kind !== 'stale' && (
        <div className="cortex-admin-error roles-detail__banner" role="alert">
          <p>{problem.message}</p>
          {problem.entries && problem.entries.length > 0 && (
            <ul className="roles-detail__problems">
              {problem.entries.map(([key, message]) => (
                <li key={key}>
                  <code>{key}</code> {message}
                </li>
              ))}
            </ul>
          )}
          {partial && <p>{t('roles.detail.partialSave')}</p>}
        </div>
      )}

      <form
        className="cortex-admin-panel roles-detail__form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (canManage && !saving && (isNew || dirty)) void save();
        }}
      >
        <div className="cortex-admin-field">
          <label htmlFor={nameId}>{t('roles.detail.fields.name')}</label>
          <input
            id={nameId}
            value={name}
            maxLength={NAME_MAX}
            disabled={!textEditable || saving}
            aria-invalid={fieldErrors.name ? true : undefined}
            aria-describedby={fieldErrors.name ? `${nameId}-error` : undefined}
            onChange={(event) => setName(event.target.value)}
          />
          {fieldErrors.name && (
            <p id={`${nameId}-error`} className="cortex-admin-form-error" role="alert">
              {fieldErrors.name}
            </p>
          )}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={descriptionId}>{t('roles.detail.fields.description')}</label>
          <textarea
            id={descriptionId}
            rows={3}
            value={description}
            maxLength={DESCRIPTION_MAX}
            disabled={!textEditable || saving}
            aria-invalid={fieldErrors.description ? true : undefined}
            aria-describedby={fieldErrors.description ? `${descriptionId}-error` : undefined}
            onChange={(event) => setDescription(event.target.value)}
          />
          {fieldErrors.description && (
            <p id={`${descriptionId}-error`} className="cortex-admin-form-error" role="alert">
              {fieldErrors.description}
            </p>
          )}
        </div>
        {role && (
          <p className="roles-detail__code">
            <span>{t('roles.detail.fields.code')}</span> <code>{role.code}</code>
            <small>{t('roles.detail.fields.codeHint')}</small>
          </p>
        )}

        {!endUser && (
          <PermissionMatrix
            catalog={catalog}
            keys={keys}
            roleName={displayName}
            superAdmin={superAdmin}
            disabled={!keysEditable || saving}
            onChange={setKeys}
          />
        )}

        {canManage && (
          <div className="roles-detail__actions">
            <button
              type="submit"
              className="cortex-admin-btn cortex-admin-btn--primary"
              disabled={saving || (!isNew && !dirty)}
            >
              {isNew
                ? t(saving ? 'roles.detail.actions.creating' : 'roles.detail.actions.create')
                : t(saving ? 'roles.detail.actions.saving' : 'roles.detail.actions.save')}
            </button>
            {role && !system && (
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--danger"
                disabled={saving}
                onClick={() => {
                  setDeleteError(null);
                  setDeleting(true);
                }}
              >
                {t('roles.detail.actions.delete')}
              </button>
            )}
          </div>
        )}
      </form>

      {deleting && role && (
        <DeleteRoleDialog
          code={role.code}
          name={displayName}
          pending={deleteRole.isPending}
          error={deleteError?.message}
          held={deleteError?.held ?? false}
          canSeeAccounts={canSeeAccounts}
          onConfirm={remove}
          onClose={() => setDeleting(false)}
        />
      )}
    </div>
  );
}
