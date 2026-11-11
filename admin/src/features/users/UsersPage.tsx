import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { ArrowsClockwise, DownloadSimple, Plus } from '@phosphor-icons/react';
import { useMeQuery } from '../auth/useAuth';
import { assignableRoles, ROLE_CODES } from './enumLabels';
import { RoleOptions } from './RoleOptions';
import { UserRow } from './UserRow';
import { createUserSchema, type CreateUserFormValues } from './userSchemas';
import {
  DEFAULT_USERS_PARAMS,
  MAX_SEARCH_LENGTH,
  parseUsersParams,
  toSearchParams,
  USER_SORTS,
  USER_STATES,
  type UsersParams,
} from './usersQueryParams';
import { usePageTitle } from '../../app/usePageTitle';
import { PageHeading } from '../../app/PageHeading';
import { MetricCard } from '../../app/MetricCard';
import { EmptyState } from '../../app/EmptyState';
import { TableRegion } from '../../app/TableRegion';
import { can } from '../../app/permissions';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import {
  useCreateUserMutation,
  useExportUsers,
  useUserStatsQuery,
  useUsersQuery,
} from './useUsers';

const DEFAULT_CREATE_ROLES = ['MEMBER'];
const SEARCH_DEBOUNCE_MS = 300;

function isFiltered(params: UsersParams): boolean {
  return params.q !== '' || params.role !== '' || params.state !== '';
}

export function UsersPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  usePageTitle(t('users.title'));
  const me = useMeQuery().data;
  const assignable = assignableRoles(me);
  const [searchParams, setSearchParams] = useSearchParams();
  const params = useMemo(() => parseUsersParams(searchParams), [searchParams]);
  const usersQuery = useUsersQuery(params);
  const statsQuery = useUserStatsQuery();
  const createMutation = useCreateUserMutation();
  const exportMutation = useExportUsers();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [searchInput, setSearchInput] = useState(params.q);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateUserFormValues>({
    resolver: zodResolver(createUserSchema),
    defaultValues: { email: '', password: '', name: '', role: 'MEMBER' },
  });

  const showView = (next: UsersParams) => setSearchParams(toSearchParams(next));
  const change = (patch: Partial<UsersParams>) => showView({ ...params, page: 0, ...patch });
  const clearFilters = () => {
    setSearchInput('');
    showView(DEFAULT_USERS_PARAMS);
  };

  useEffect(() => {
    const typed = searchInput.trim().slice(0, MAX_SEARCH_LENGTH).trim();
    if (typed === params.q) return undefined;
    const timer = setTimeout(
      () => setSearchParams(toSearchParams({ ...params, q: typed, page: 0 }), { replace: true }),
      SEARCH_DEBOUNCE_MS,
    );
    return () => clearTimeout(timer);
  }, [searchInput, params, setSearchParams]);

  // A search that changed from outside the field (Clear filters, back button) replaces what is typed.
  useEffect(() => {
    setSearchInput((typed) =>
      typed.trim().slice(0, MAX_SEARCH_LENGTH).trim() === params.q ? typed : params.q,
    );
  }, [params.q]);

  const onCreate = handleSubmit((values) => {
    createMutation.mutate(values, {
      onSuccess: () => {
        reset({ email: '', password: '', name: '', role: 'MEMBER' });
        setShowCreateForm(false);
      },
    });
  });

  const createError =
    createMutation.error instanceof ApiError ? createMutation.error.problem : undefined;
  const exportError = exportMutation.error;
  const exportRefused = exportError instanceof ApiError && exportError.status === 422;
  const page = usersQuery.data;
  const stats = statsQuery.data;
  const pages = page ? Math.max(1, Math.ceil(page.total / page.size), page.page + 1) : 1;
  const hasPrevious = params.page > 0;
  const hasNext = page !== undefined && params.page + 1 < pages;
  const filtered = isFiltered(params);
  const count = (value: number | undefined) =>
    value === undefined ? '—' : formatNumber(value, locale);
  const refresh = () => {
    void usersQuery.refetch();
    void statsQuery.refetch();
  };

  let emptyState = null;
  if (page?.items.length === 0) {
    if (page.page > 0) {
      emptyState = (
        <EmptyState
          title={t('users.directory.emptyPage')}
          action={
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              onClick={() => change({ page: 0 })}
            >
              {t('users.directory.backToFirstPage')}
            </button>
          }
        />
      );
    } else if (filtered) {
      emptyState = (
        <EmptyState
          title={t('users.directory.noMatch')}
          action={
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              onClick={clearFilters}
            >
              {t('users.directory.clearFilters')}
            </button>
          }
        />
      );
    } else {
      emptyState = (
        <EmptyState
          title={t('users.directory.empty')}
          description={t('users.directory.emptyHint')}
        />
      );
    }
  }

  return (
    <div className="cortex-admin-users">
      <PageHeading
        eyebrow={t('users.eyebrow')}
        title={t('users.title')}
        description={t('users.description')}
        actions={
          <>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              onClick={refresh}
              disabled={usersQuery.isFetching || statsQuery.isFetching}
            >
              <ArrowsClockwise size={16} aria-hidden="true" />
              <span>
                {usersQuery.isFetching || statsQuery.isFetching
                  ? t('users.directory.refreshing')
                  : t('users.directory.refresh')}
              </span>
            </button>
            {can(me, 'user.account.export') && (
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--ghost"
                onClick={() => exportMutation.mutate(params)}
                disabled={exportMutation.isPending}
              >
                <DownloadSimple size={16} aria-hidden="true" />
                <span>
                  {exportMutation.isPending ? t('users.export.busy') : t('users.export.button')}
                </span>
              </button>
            )}
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--primary"
              onClick={() => setShowCreateForm((open) => !open)}
            >
              <Plus size={16} aria-hidden="true" />
              <span>{showCreateForm ? t('users.cancel') : t('users.addUser')}</span>
            </button>
          </>
        }
      />

      {exportError && (
        <div className="cortex-admin-error" role="alert">
          {exportRefused ? (
            <>
              <p>{exportError.message}</p>
              <p>{t('users.export.tooManyRows')}</p>
            </>
          ) : (
            <>
              <p>{t('users.export.error')}</p>
              <button
                type="button"
                className="cortex-admin-btn"
                onClick={() => exportMutation.mutate(params)}
                disabled={exportMutation.isPending}
              >
                {t('users.export.retry')}
              </button>
            </>
          )}
        </div>
      )}

      {stats && (
        <section className="cortex-admin-metric-grid" aria-label={t('users.stats.label')}>
          <MetricCard label={t('users.stats.total')} value={count(stats.total)} />
          <MetricCard label={t('users.stats.active')} value={count(stats.active)} />
          <MetricCard
            label={t('users.stats.pendingConfirmation')}
            value={count(stats.pendingConfirmation)}
          />
          <MetricCard label={t('users.stats.locked')} value={count(stats.locked)} />
          <MetricCard label={t('users.stats.suspended')} value={count(stats.suspended)} />
          <MetricCard label={t('users.stats.newThisWeek')} value={count(stats.newLast7Days)} />
        </section>
      )}

      {showCreateForm && (
        <form
          className="cortex-admin-panel"
          onSubmit={onCreate}
          noValidate
          aria-label={t('users.create.form')}
        >
          <header className="cortex-admin-section-heading">
            <div>
              <h2>{t('users.create.title')}</h2>
              <p>{t('users.create.hint')}</p>
            </div>
          </header>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-email">{t('users.create.email')}</label>
            <input id="new-user-email" type="email" autoComplete="off" {...register('email')} />
            {errors.email?.message && (
              <p className="cortex-admin-form-error" role="alert">
                {t(errors.email.message)}
              </p>
            )}
            {createError?.errors?.email && (
              <p className="cortex-admin-form-error" role="alert">
                {createError.errors.email}
              </p>
            )}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-password">{t('users.create.password')}</label>
            <input
              id="new-user-password"
              type="password"
              autoComplete="new-password"
              {...register('password')}
            />
            {errors.password?.message && (
              <p className="cortex-admin-form-error" role="alert">
                {t(errors.password.message)}
              </p>
            )}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-name">{t('users.create.name')}</label>
            <input id="new-user-name" {...register('name')} />
            {errors.name?.message && (
              <p className="cortex-admin-form-error" role="alert">
                {t(errors.name.message)}
              </p>
            )}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-role">{t('users.create.role')}</label>
            <select id="new-user-role" {...register('role')}>
              <RoleOptions roles={assignable.length > 0 ? assignable : DEFAULT_CREATE_ROLES} />
            </select>
          </div>
          {createError && !createError.errors && (
            <p className="cortex-admin-form-error" role="alert">
              {createError.detail}
            </p>
          )}
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={createMutation.isPending}
          >
            {createMutation.isPending ? t('users.create.submitting') : t('users.create.submit')}
          </button>
        </form>
      )}

      <section className="cortex-admin-panel" aria-label={t('users.directory.label')}>
        <header className="cortex-admin-section-heading">
          <div>
            <h2>{t('users.directory.title')}</h2>
          </div>
        </header>
        <div className="cortex-admin-filters">
          <div className="cortex-admin-field">
            <label htmlFor="users-search">{t('users.directory.search')}</label>
            <input
              id="users-search"
              type="search"
              value={searchInput}
              maxLength={MAX_SEARCH_LENGTH}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder={t('users.directory.searchPlaceholder')}
            />
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="users-state">{t('users.directory.state')}</label>
            <select
              id="users-state"
              value={params.state}
              onChange={(event) =>
                change({
                  state: parseUsersParams(new URLSearchParams({ state: event.target.value })).state,
                })
              }
            >
              <option value="">{t('users.directory.allStates')}</option>
              {USER_STATES.map((state) => (
                <option key={state} value={state}>
                  {t(`users.state.${state}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="users-role">{t('users.directory.role')}</label>
            <select
              id="users-role"
              value={params.role}
              onChange={(event) => change({ role: event.target.value })}
            >
              <option value="">{t('users.directory.allRoles')}</option>
              <RoleOptions roles={ROLE_CODES} />
              {params.role !== '' && !ROLE_CODES.some((code) => code === params.role) && (
                <option value={params.role}>{params.role}</option>
              )}
            </select>
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="users-sort">{t('users.sort.label')}</label>
            <select
              id="users-sort"
              value={params.sort}
              onChange={(event) =>
                change({
                  sort: parseUsersParams(new URLSearchParams({ sort: event.target.value })).sort,
                })
              }
            >
              {USER_SORTS.map((sort) => (
                <option key={sort} value={sort}>
                  {t(`users.sort.${sort}`)}
                </option>
              ))}
            </select>
          </div>
        </div>
        {usersQuery.isPending && (
          <output className="cortex-admin-loading">{t('users.directory.loading')}</output>
        )}
        {usersQuery.isError && (
          <div className="cortex-admin-error" role="alert">
            <p>{t('users.directory.loadError')}</p>
            <button type="button" className="cortex-admin-btn" onClick={() => usersQuery.refetch()}>
              {t('users.directory.retry')}
            </button>
            {filtered && (
              <button type="button" className="cortex-admin-btn" onClick={clearFilters}>
                {t('users.directory.clearFilters')}
              </button>
            )}
          </div>
        )}
        {emptyState}
        {page && page.items.length > 0 && (
          <TableRegion label={t('users.directory.tableLabel')} busy={usersQuery.isPlaceholderData}>
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{t('users.columns.name')}</th>
                  <th scope="col">{t('users.columns.email')}</th>
                  <th scope="col">{t('users.columns.role')}</th>
                  <th scope="col">{t('users.columns.state')}</th>
                  <th scope="col">{t('users.columns.joined')}</th>
                  <th scope="col">{t('users.columns.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((user) => (
                  <UserRow key={user.id} user={user} />
                ))}
              </tbody>
            </table>
          </TableRegion>
        )}
        {page && (page.items.length > 0 || page.page > 0) && (
          <nav className="cortex-admin-pager" aria-label={t('users.pagination.label')}>
            <button
              type="button"
              className="cortex-admin-btn"
              aria-label={t('users.pagination.previous')}
              aria-disabled={!hasPrevious}
              onClick={() => hasPrevious && change({ page: params.page - 1 })}
            >
              {t('users.pagination.previous')}
            </button>
            <span>
              {t('users.pagination.summary', {
                page: formatNumber(params.page + 1, locale),
                pages: formatNumber(pages, locale),
                count: page.total,
                total: formatNumber(page.total, locale),
              })}
            </span>
            <button
              type="button"
              className="cortex-admin-btn"
              aria-label={t('users.pagination.next')}
              aria-disabled={!hasNext}
              onClick={() => hasNext && change({ page: params.page + 1 })}
            >
              {t('users.pagination.next')}
            </button>
          </nav>
        )}
      </section>
    </div>
  );
}
