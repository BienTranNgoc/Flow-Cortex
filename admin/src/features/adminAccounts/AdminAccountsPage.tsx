import { ApiError, type AdminAccountResponse } from '@flow-cortex/api-client';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pager, Timestamp } from '../../app/AdminList';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { ROLE_CODES, roleLabel, STAFF_ROLES, statusLabel } from '../users/enumLabels';
import { AccountDialog, type AccountAction } from './AccountDialog';
import {
  useAdminAccounts,
  useAdminRoles,
  useChangeAdminRoleMutation,
  useSetAdminAccountActiveMutation,
} from './useAdminAccounts';

const SEARCH_DEBOUNCE_MS = 300;
const ADMIN_ROLE_CODES: readonly string[] = ['ADMIN', ...STAFF_ROLES];

interface DialogState {
  readonly account: AdminAccountResponse;
  readonly action: AccountAction;
}

export function AdminAccountsPage() {
  const { t } = useTranslation();
  usePageTitle(t('adminAccounts.title'));
  const me = useMeQuery().data;
  const canManage = can(me, 'system.admin_account.manage');
  const [page, setPage] = useState(0);
  const [roleFilter, setRoleFilter] = useState('');
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [notice, setNotice] = useState('');
  const query = useAdminAccounts(page, roleFilter, q);
  const roles = useAdminRoles(can(me, 'system.role.view'));
  const changeRole = useChangeAdminRoleMutation();
  const setActive = useSetAdminAccountActiveMutation();
  const mutation = dialog?.action === 'role' ? changeRole : setActive;

  useEffect(() => {
    const timer = setTimeout(() => {
      setQ(search.trim());
      setPage(0);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search]);

  const roleCodes = roles.data?.map((role) => role.code) ?? ROLE_CODES;
  const filterCodes = roles.data
    ? roles.data.filter((role) => role.staff || role.superAdmin).map((role) => role.code)
    : ADMIN_ROLE_CODES;
  const data = query.data;
  const filtered = roleFilter !== '' || q !== '';

  const closeDialog = () => {
    setDialog(null);
    changeRole.reset();
    setActive.reset();
  };

  const confirm = (role: string) => {
    if (!dialog) return;
    const { account, action } = dialog;
    const done = (message: string) => {
      setNotice(message);
      closeDialog();
    };
    if (action === 'role') {
      changeRole.mutate(
        { id: account.id, role },
        {
          onSuccess: () =>
            done(
              t('adminAccounts.notice.roleChanged', {
                name: account.name,
                role: roleLabel(t, role),
              }),
            ),
        },
      );
    } else {
      const active = action === 'activate';
      setActive.mutate(
        { id: account.id, active },
        {
          onSuccess: () =>
            done(
              t(active ? 'adminAccounts.notice.activated' : 'adminAccounts.notice.deactivated', {
                name: account.name,
              }),
            ),
        },
      );
    }
  };

  const problem = mutation.error instanceof ApiError ? mutation.error.problem : undefined;
  const dialogError = mutation.error
    ? (problem?.detail ?? t('adminAccounts.errors.generic'))
    : undefined;

  const open = (account: AdminAccountResponse, action: AccountAction) => {
    setNotice('');
    setDialog({ account, action });
  };

  const columns = {
    name: t('adminAccounts.columns.name'),
    email: t('adminAccounts.columns.email'),
    role: t('adminAccounts.columns.role'),
    status: t('adminAccounts.columns.status'),
    created: t('adminAccounts.columns.created'),
    actions: t('adminAccounts.columns.actions'),
  };

  return (
    <div className="cortex-admin-accounts">
      <PageHeading
        eyebrow={t('adminAccounts.eyebrow')}
        title={t('adminAccounts.title')}
        description={t('adminAccounts.description')}
      />
      <div className="cortex-admin-panel">
        <div className="cortex-admin-toolbar">
          <div className="cortex-admin-field">
            <label htmlFor="admin-accounts-role">{t('adminAccounts.filters.role')}</label>
            <select
              id="admin-accounts-role"
              value={roleFilter}
              onChange={(event) => {
                setRoleFilter(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t('adminAccounts.filters.allRoles')}</option>
              {filterCodes.map((code) => (
                <option key={code} value={code}>
                  {roleLabel(t, code)}
                </option>
              ))}
            </select>
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="admin-accounts-search">{t('adminAccounts.filters.search')}</label>
            <input
              id="admin-accounts-search"
              type="search"
              value={search}
              placeholder={t('adminAccounts.filters.searchPlaceholder')}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('adminAccounts.refresh')}
          </button>
        </div>

        {notice && (
          <output className="cortex-admin-form-success">{notice}</output>
        )}

        {query.isError && (
          <div className="cortex-admin-error">
            <p role="alert">{t('adminAccounts.errors.load')}</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              {t('adminAccounts.retry')}
            </button>
          </div>
        )}
        {(query.isPending || query.isPlaceholderData) && (
          <output className="cortex-admin-loading">{t('adminAccounts.loading')}</output>
        )}
        {data && query.isError && <p className="cortex-admin-stale">{t('adminAccounts.stale')}</p>}
        {data?.items.length === 0 && (
          <div className="cortex-admin-empty">
            <p>{filtered ? t('adminAccounts.noMatch') : t('adminAccounts.empty')}</p>
            {data.page > 0 && (
              <button type="button" className="cortex-admin-btn" onClick={() => setPage(0)}>
                {t('common.shell.list.backToFirstPage')}
              </button>
            )}
          </div>
        )}
        {data && data.items.length > 0 && (
          <TableRegion label={t('adminAccounts.tableLabel')} busy={query.isPlaceholderData}>
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{columns.name}</th>
                  <th scope="col">{columns.email}</th>
                  <th scope="col">{columns.role}</th>
                  <th scope="col">{columns.status}</th>
                  <th scope="col">{columns.created}</th>
                  {canManage && <th scope="col">{columns.actions}</th>}
                </tr>
              </thead>
              <tbody>
                {data.items.map((account) => (
                  <tr key={account.id}>
                    <td data-label={columns.name}>{account.name}</td>
                    <td data-label={columns.email}>{account.email}</td>
                    <td data-label={columns.role}>
                      <StatusPill tone="info" label={roleLabel(t, account.role)} />
                    </td>
                    <td data-label={columns.status}>
                      {account.adminDisabled ? (
                        <StatusPill tone="warning" label={t('adminAccounts.status.deactivated')} />
                      ) : (
                        <StatusPill
                          tone={account.status === 'ACTIVE' ? 'success' : 'warning'}
                          label={statusLabel(t, account.status)}
                        />
                      )}
                    </td>
                    <td data-label={columns.created}>
                      <Timestamp value={account.createdAt} />
                    </td>
                    {canManage && (
                      <td data-label={columns.actions}>
                        {account.id === me?.id ? (
                          <span>—</span>
                        ) : (
                          <div className="cortex-admin-row-actions">
                            <button
                              type="button"
                              className="cortex-admin-btn cortex-admin-btn--sm"
                              aria-label={t('adminAccounts.actions.changeRoleFor', {
                                name: account.name,
                              })}
                              onClick={() => open(account, 'role')}
                            >
                              {t('adminAccounts.actions.changeRole')}
                            </button>
                            <button
                              type="button"
                              className={
                                account.adminDisabled
                                  ? 'cortex-admin-btn cortex-admin-btn--sm'
                                  : 'cortex-admin-btn cortex-admin-btn--sm cortex-admin-btn--danger'
                              }
                              aria-label={t(
                                account.adminDisabled
                                  ? 'adminAccounts.actions.activateFor'
                                  : 'adminAccounts.actions.deactivateFor',
                                { name: account.name },
                              )}
                              onClick={() =>
                                open(account, account.adminDisabled ? 'activate' : 'deactivate')
                              }
                            >
                              {t(
                                account.adminDisabled
                                  ? 'adminAccounts.actions.activate'
                                  : 'adminAccounts.actions.deactivate',
                              )}
                            </button>
                          </div>
                        )}
                      </td>
                    )}
                  </tr>
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
            nounKey="adminAccounts.noun"
            onPageChange={setPage}
          />
        )}
      </div>

      {dialog && (
        <AccountDialog
          key={`${dialog.account.id}-${dialog.action}`}
          account={dialog.account}
          action={dialog.action}
          roles={roleCodes}
          pending={mutation.isPending}
          error={dialogError}
          onConfirm={confirm}
          onClose={closeDialog}
        />
      )}
    </div>
  );
}
