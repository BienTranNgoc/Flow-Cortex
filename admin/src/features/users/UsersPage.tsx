import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import { UserRow } from './UserRow';
import './UsersPage.css';
import { createUserSchema, type CreateUserFormValues } from './userSchemas';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useCreateUserMutation, useUsersQuery } from './useUsers';

const EMPTY_USERS: NonNullable<ReturnType<typeof useUsersQuery>['data']> = [];

export function UsersPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  usePageTitle(t('users.title'));
  const usersQuery = useUsersQuery();
  const createMutation = useCreateUserMutation();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateUserFormValues>({
    resolver: zodResolver(createUserSchema),
    defaultValues: { email: '', password: '', name: '', role: 'MEMBER' },
  });

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
  const users = usersQuery.data ?? EMPTY_USERS;
  const visibleUsers = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase();
    return users.filter((user) => {
      const matchesSearch =
        !needle ||
        user.email?.toLocaleLowerCase().includes(needle) ||
        user.name?.toLocaleLowerCase().includes(needle);
      return (
        matchesSearch &&
        (!statusFilter || user.status === statusFilter) &&
        (!roleFilter || user.role === roleFilter)
      );
    });
  }, [users, search, statusFilter, roleFilter]);
  const sevenDaysAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
  const newThisWeek = users.filter(
    (user) => user.createdAt && Date.parse(user.createdAt) >= sevenDaysAgo,
  ).length;

  return (
    <main className="users-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">{t('users.eyebrow')}</p>
          <h1>{t('users.title')}</h1>
          <p className="page-description">{t('users.description')}</p>
        </div>
        <button
          className="button-primary"
          type="button"
          onClick={() => setShowCreateForm((open) => !open)}
        >
          {showCreateForm ? t('users.cancel') : t('users.addUser')}
        </button>
      </div>

      {usersQuery.data && (
        <section className="metric-grid" aria-label={t('users.stats.label')}>
          <article className="metric-card">
            <span>{t('users.stats.total')}</span>
            <strong>{formatNumber(users.length, locale)}</strong>
          </article>
          <article className="metric-card">
            <span>{t('users.stats.active')}</span>
            <strong>
              {formatNumber(users.filter((user) => user.status === 'ACTIVE').length, locale)}
            </strong>
          </article>
          <article className="metric-card">
            <span>{t('users.stats.suspended')}</span>
            <strong>
              {formatNumber(users.filter((user) => user.status === 'SUSPENDED').length, locale)}
            </strong>
          </article>
          <article className="metric-card">
            <span>{t('users.stats.newThisWeek')}</span>
            <strong>{formatNumber(newThisWeek, locale)}</strong>
          </article>
        </section>
      )}

      {showCreateForm && (
        <form
          className="create-user-panel"
          onSubmit={onCreate}
          noValidate
          aria-label={t('users.create.form')}
        >
          <h2>{t('users.create.title')}</h2>
          <div className="field">
            <label htmlFor="new-user-email">{t('users.create.email')}</label>
            <input id="new-user-email" type="email" autoComplete="off" {...register('email')} />
            {errors.email?.message && <p role="alert">{t(errors.email.message)}</p>}
            {createError?.errors?.email && <p role="alert">{createError.errors.email}</p>}
          </div>
          <div className="field">
            <label htmlFor="new-user-password">{t('users.create.password')}</label>
            <input
              id="new-user-password"
              type="password"
              autoComplete="new-password"
              {...register('password')}
            />
            {errors.password?.message && <p role="alert">{t(errors.password.message)}</p>}
          </div>
          <div className="field">
            <label htmlFor="new-user-name">{t('users.create.name')}</label>
            <input id="new-user-name" {...register('name')} />
            {errors.name?.message && <p role="alert">{t(errors.name.message)}</p>}
          </div>
          <div className="field">
            <label htmlFor="new-user-role">{t('users.create.role')}</label>
            <select id="new-user-role" {...register('role')}>
              <option value="MEMBER">{t('users.roleOptions.MEMBER')}</option>
              <option value="ADMIN">{t('users.roleOptions.ADMIN')}</option>
            </select>
          </div>
          {createError && !createError.errors && <p role="alert">{createError.detail}</p>}
          <button type="submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? t('users.create.submitting') : t('users.create.submit')}
          </button>
        </form>
      )}

      <section className="users-list-panel" aria-label={t('users.directory.label')}>
        <div className="section-heading">
          <div>
            <h2>{t('users.directory.title')}</h2>
            {usersQuery.data && (
              <p>
                {t('users.directory.count', {
                  visible: formatNumber(visibleUsers.length, locale),
                  total: formatNumber(users.length, locale),
                })}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={() => usersQuery.refetch()}
            disabled={usersQuery.isFetching}
          >
            {usersQuery.isFetching ? t('users.directory.refreshing') : t('users.directory.refresh')}
          </button>
        </div>
        <div className="user-filters">
          <div className="field">
            <label htmlFor="users-search">{t('users.directory.search')}</label>
            <input
              id="users-search"
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t('users.directory.searchPlaceholder')}
            />
          </div>
          <div className="field">
            <label htmlFor="users-status">{t('users.directory.status')}</label>
            <select
              id="users-status"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
            >
              <option value="">{t('users.directory.allStatuses')}</option>
              <option value="ACTIVE">{t('users.status.ACTIVE')}</option>
              <option value="SUSPENDED">{t('users.status.SUSPENDED')}</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="users-role">{t('users.directory.role')}</label>
            <select
              id="users-role"
              value={roleFilter}
              onChange={(event) => setRoleFilter(event.target.value)}
            >
              <option value="">{t('users.directory.allRoles')}</option>
              <option value="ADMIN">{t('users.roleOptions.ADMIN')}</option>
              <option value="MEMBER">{t('users.roleOptions.MEMBER')}</option>
              <option value="CREATOR">{t('users.roleOptions.CREATOR')}</option>
            </select>
          </div>
        </div>
        {usersQuery.isLoading && <output>{t('users.directory.loading')}</output>}
        {usersQuery.isError && (
          <>
            <p role="alert">{t('users.directory.loadError')}</p>
            <button type="button" onClick={() => usersQuery.refetch()}>
              {t('users.directory.retry')}
            </button>
          </>
        )}
        {usersQuery.data?.length === 0 && (
          <p className="list-empty">{t('users.directory.empty')}</p>
        )}
        {usersQuery.data && users.length > 0 && visibleUsers.length === 0 && (
          <p className="list-empty">{t('users.directory.noMatch')}</p>
        )}
        {visibleUsers.length > 0 && (
          <div className="users-table-wrapper">
            <table className="users-table">
              <thead>
                <tr>
                  <th scope="col">{t('users.columns.name')}</th>
                  <th scope="col">{t('users.columns.email')}</th>
                  <th scope="col">{t('users.columns.role')}</th>
                  <th scope="col">{t('users.columns.status')}</th>
                  <th scope="col">{t('users.columns.joined')}</th>
                  <th scope="col">{t('users.columns.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {visibleUsers.map((user) => (
                  <UserRow key={user.id} user={user} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
