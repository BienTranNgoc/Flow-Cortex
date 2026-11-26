import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { RbacRuntimeNotice } from './RbacRuntimeNotice';
import { RbacRuntimeSwitch } from './RbacRuntimeSwitch';
import { accountsOfRoleHref, isAdminRole, roleDisplayName } from './roleDisplay';
import { useRolesEditable } from './useRbacRuntime';
import { useRolesQuery } from './useRoles';
import './roles.css';

export function RolesPage() {
  const { t } = useTranslation();
  usePageTitle(t('roles.title'));
  const me = useMeQuery().data;
  const { canManage, runtime, editable } = useRolesEditable();
  const canSeeAccounts = can(me, 'system.admin_account.view');
  const query = useRolesQuery();
  const roles = query.data?.filter(isAdminRole);
  const hasCustom = roles?.some((role) => !role.system) ?? false;

  const columns = {
    name: t('roles.list.columns.name'),
    code: t('roles.list.columns.code'),
    type: t('roles.list.columns.type'),
    accounts: t('roles.list.columns.accounts'),
  };

  const createClass = 'cortex-admin-btn cortex-admin-btn--primary';
  const create = !canManage ? undefined : editable ? (
    <Link className={createClass} to="/roles/new">
      {t('roles.list.create')}
    </Link>
  ) : (
    <button type="button" className={createClass} disabled>
      {t('roles.list.create')}
    </button>
  );

  return (
    <div className="roles-page">
      <PageHeading
        eyebrow={t('roles.eyebrow')}
        title={t('roles.title')}
        description={t('roles.description')}
        actions={create}
      />
      {canManage && (
        <>
          <RbacRuntimeSwitch runtime={runtime} />
          <RbacRuntimeNotice runtime={runtime} />
        </>
      )}
      <div className="cortex-admin-panel">
        {query.isPending && (
          <output className="cortex-admin-loading">{t('roles.list.loading')}</output>
        )}
        {query.isError && (
          <div className="cortex-admin-error">
            <p role="alert">{t('roles.list.errors.load')}</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              {t('roles.list.retry')}
            </button>
          </div>
        )}
        {roles && roles.length > 0 && (
          <TableRegion label={t('roles.list.tableLabel')} busy={query.isFetching}>
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{columns.name}</th>
                  <th scope="col">{columns.code}</th>
                  <th scope="col">{columns.type}</th>
                  <th scope="col">{columns.accounts}</th>
                </tr>
              </thead>
              <tbody>
                {roles.map((role) => {
                  const name = roleDisplayName(t, role);
                  return (
                    <tr key={role.code}>
                      <td data-label={columns.name}>
                        <Link
                          className="cortex-admin-link roles-page__name"
                          to={`/roles/${encodeURIComponent(role.code)}`}
                          aria-label={t('roles.list.open', { name })}
                        >
                          {name}
                        </Link>
                      </td>
                      <td data-label={columns.code}>
                        <code>{role.code}</code>
                      </td>
                      <td data-label={columns.type}>
                        <StatusPill
                          tone={role.system ? 'info' : 'success'}
                          label={t(
                            role.system ? 'roles.list.type.system' : 'roles.list.type.custom',
                          )}
                        />
                      </td>
                      <td data-label={columns.accounts}>
                        {canSeeAccounts ? (
                          <Link
                            className="cortex-admin-link roles-page__count"
                            to={accountsOfRoleHref(role.code)}
                            aria-label={t('roles.list.accountsLinkLabel', { name })}
                          >
                            {t('roles.list.accountsLink', { count: role.accountCount })}
                          </Link>
                        ) : (
                          role.accountCount
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableRegion>
        )}
        {roles && !hasCustom && (
          <EmptyState
            title={t('roles.list.empty')}
            {...(canManage ? { description: t('roles.list.emptyCreate'), action: create } : {})}
          />
        )}
      </div>
    </div>
  );
}
