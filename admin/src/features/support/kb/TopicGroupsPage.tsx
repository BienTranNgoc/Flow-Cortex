import type { AdminSupportKbGroup } from '@flow-cortex/api-client';
import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import { usePageTitle } from '../../../app/usePageTitle';
import { supportProblemCode } from '../unwrapSupport';
import './kb.css';
import {
  useCreateGroupMutation,
  useDeleteGroupMutation,
  useGroupsQuery,
  useRenameGroupMutation,
  useReorderGroupsMutation,
} from './useKnowledgeBase';

const MAX_NAME = 60;
const groupNameSchema = z.string().trim().min(1).max(MAX_NAME);

/** The trimmed name the server accepts, or `null`. */
function parseName(name: string): string | null {
  const result = groupNameSchema.safeParse(name);
  return result.success ? result.data : null;
}

/** The console message for a refused group write; server text is never shown. */
function groupProblem(error: unknown, t: TFunction): string {
  switch (supportProblemCode(error)) {
    case 'group-not-empty': {
      const count = error instanceof ApiError ? error.problem?.articleCount : undefined;
      return t('support.problems.group-not-empty', { count: count ?? 1 });
    }
    case 'group-name-taken':
      return t('support.problems.group-name-taken');
    case 'group-order-mismatch':
      return t('support.problems.group-order-mismatch');
    case 'group-not-found':
      return t('support.problems.group-not-found');
    case 'validation-failed':
      return t('support.groups.name');
    default:
      return t('support.common.saveFailed');
  }
}

export function TopicGroupsPage() {
  const { t } = useTranslation();
  usePageTitle(t('support.groups.title'));
  const groupsQuery = useGroupsQuery();
  const create = useCreateGroupMutation();
  const rename = useRenameGroupMutation();
  const remove = useDeleteGroupMutation();
  const reorder = useReorderGroupsMutation();
  const [newName, setNewName] = useState('');
  const [newNameInvalid, setNewNameInvalid] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const pendingFocus = useRef<string | null>(null);
  const newNameId = useId();
  const listId = useId();
  const busy = create.isPending || rename.isPending || remove.isPending || reorder.isPending;
  const groups = groupsQuery.data
    ? [...groupsQuery.data].sort((a, b) => a.position - b.position)
    : [];
  const buttonId = (groupId: string, offset: -1 | 1) =>
    `${listId}-${groupId}-${offset < 0 ? 'up' : 'down'}`;

  // Moving a row re-inserts it in the list, which drops focus from the button that moved it.
  useEffect(() => {
    if (pendingFocus.current) {
      document.getElementById(pendingFocus.current)?.focus();
      pendingFocus.current = null;
    }
  }, [groupsQuery.data]);

  const started = () => {
    setProblem(null);
    setAnnouncement('');
  };
  const onError = (error: unknown) => setProblem(groupProblem(error, t));

  const onCreate = (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    const name = parseName(newName);
    if (name === null) {
      setNewNameInvalid(true);
      return;
    }
    setNewNameInvalid(false);
    started();
    create.mutate(name, {
      onSuccess: () => {
        setNewName('');
        setAnnouncement(t('support.groups.created', { name }));
      },
      onError,
    });
  };

  const move = (index: number, offset: -1 | 1) => {
    const group = groups[index];
    if (busy || !group) return;
    const ids = groups.map((item) => item.id);
    ids.splice(index, 1);
    ids.splice(index + offset, 0, group.id);
    started();
    reorder.mutate(ids, {
      onSuccess: () => {
        pendingFocus.current = buttonId(group.id, offset);
        const position = index + offset + 1;
        setAnnouncement(
          t(offset < 0 ? 'support.groups.movedUp' : 'support.groups.movedDown', {
            name: group.name,
            position,
          }),
        );
      },
      onError,
    });
  };

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.groups.eyebrow')}</p>
          <h1>{t('support.groups.title')}</h1>
          <p className="cortex-admin-page-description">{t('support.groups.description')}</p>
        </div>
      </div>

      <form className="support-inline-form" onSubmit={onCreate} noValidate>
        <div className="cortex-admin-field">
          <label htmlFor={newNameId}>{t('support.groups.newLabel')}</label>
          <input
            id={newNameId}
            className="support-input"
            value={newName}
            maxLength={MAX_NAME}
            aria-invalid={newNameInvalid || undefined}
            aria-describedby={newNameInvalid ? `${newNameId}-error` : undefined}
            onChange={(event) => setNewName(event.target.value)}
          />
          {newNameInvalid && (
            <p id={`${newNameId}-error`} role="alert">
              {t('support.groups.name')}
            </p>
          )}
        </div>
        <button type="submit" className="cortex-admin-btn cortex-admin-btn--primary" aria-disabled={busy}>
          {t('support.groups.add')}
        </button>
      </form>

      {problem && <p role="alert">{problem}</p>}
      <p role="status" className="support-announcement">
        {announcement}
      </p>

      {groupsQuery.isPending && <output>{t('support.common.loading')}</output>}
      {groupsQuery.isError && !groupsQuery.data && (
        <div className="cortex-admin-error">
          <p>{t('support.common.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => groupsQuery.refetch()}>
            {t('support.common.retry')}
          </button>
        </div>
      )}
      {groupsQuery.data?.length === 0 && <p className="cortex-admin-empty">{t('support.groups.empty')}</p>}
      {groups.length > 0 && (
        <ol className="support-group-list" aria-label={t('support.groups.listLabel')}>
          {groups.map((group, index) => (
            <GroupRow
              key={group.id}
              group={group}
              first={index === 0}
              last={index === groups.length - 1}
              busy={busy}
              upId={buttonId(group.id, -1)}
              downId={buttonId(group.id, 1)}
              onMove={(offset) => move(index, offset)}
              onRename={(name) => {
                if (busy) return;
                started();
                rename.mutate(
                  { id: group.id, name },
                  {
                    onSuccess: () => setAnnouncement(t('support.groups.renamed', { name })),
                    onError,
                  },
                );
              }}
              onDelete={() => {
                if (busy) return;
                started();
                remove.mutate(group.id, {
                  onSuccess: () => setAnnouncement(t('support.groups.deleted', { name: group.name })),
                  onError,
                });
              }}
            />
          ))}
        </ol>
      )}
    </main>
  );
}

function GroupRow({
  group,
  first,
  last,
  busy,
  upId,
  downId,
  onMove,
  onRename,
  onDelete,
}: Readonly<{
  group: AdminSupportKbGroup;
  first: boolean;
  last: boolean;
  busy: boolean;
  upId: string;
  downId: string;
  onMove: (offset: -1 | 1) => void;
  onRename: (name: string) => void;
  onDelete: () => void;
}>) {
  const { t } = useTranslation();
  const [name, setName] = useState(group.name);
  const [invalid, setInvalid] = useState(false);
  const inputId = useId();
  const errorId = `${inputId}-error`;
  const unchanged = name.trim() === group.name;

  useEffect(() => {
    setName(group.name);
    setInvalid(false);
  }, [group.name]);

  return (
    <li className="support-group-row">
      <div className="support-group-name">
        <strong>{group.name}</strong>
        <span className="cortex-admin-table-job-message">
          {t('support.groups.articleCount', { count: group.articleCount })}
        </span>
      </div>
      <form
        className="support-inline-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          const parsed = parseName(name);
          if (parsed === null) {
            setInvalid(true);
            return;
          }
          setInvalid(false);
          if (parsed !== group.name) onRename(parsed);
        }}
      >
        <div className="cortex-admin-field">
          <label className="cortex-admin-visually-hidden" htmlFor={inputId}>
            {t('support.groups.nameLabel', { name: group.name })}
          </label>
          <input
            id={inputId}
            className="support-input"
            value={name}
            maxLength={MAX_NAME}
            aria-invalid={invalid || undefined}
            aria-describedby={invalid ? errorId : undefined}
            onChange={(event) => setName(event.target.value)}
          />
          {invalid && (
            <p id={errorId} role="alert">
              {t('support.groups.name')}
            </p>
          )}
        </div>
        <button type="submit" className="cortex-admin-btn" aria-disabled={busy || unchanged}>
          {t('support.groups.rename')}
        </button>
      </form>
      <div className="support-actions">
        <button
          id={upId}
          type="button"
          className="cortex-admin-btn"
          aria-label={t('support.groups.moveUp', { name: group.name })}
          aria-disabled={busy || first}
          onClick={() => !first && onMove(-1)}
        >
          {t('support.groups.up')}
        </button>
        <button
          id={downId}
          type="button"
          className="cortex-admin-btn"
          aria-label={t('support.groups.moveDown', { name: group.name })}
          aria-disabled={busy || last}
          onClick={() => !last && onMove(1)}
        >
          {t('support.groups.down')}
        </button>
        <button
          type="button"
          className="cortex-admin-btn cortex-admin-btn--danger"
          aria-label={t('support.groups.remove', { name: group.name })}
          aria-disabled={busy}
          onClick={onDelete}
        >
          {t('support.groups.delete')}
        </button>
      </div>
    </li>
  );
}
