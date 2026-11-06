import { zodResolver } from '@hookform/resolvers/zod';
import {
  ApiError,
  type AdminSupportKbArticle,
  type AdminSupportKbArticleRequest,
  type AdminSupportKbGroup,
} from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useForm, type FieldPath } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { usePageTitle } from '../../../app/usePageTitle';
import { Markdown } from '../Markdown';
import { supportProblemCode } from '../unwrapSupport';
import './kb.css';
import {
  useArticleQuery,
  useArticleStatusMutation,
  useGroupsQuery,
  useRefreshArticle,
  useRefreshGroups,
  useSaveArticleMutation,
} from './useKnowledgeBase';

const NEW_ARTICLE = 'new';
const FEATURED_POSITIONS = ['1', '2', '3'] as const;

// Validation messages are locale keys, translated where they render.
const articleSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, 'support.editor.validation.title')
    .max(150, 'support.editor.validation.title'),
  slug: z
    .string()
    .min(1, 'support.editor.validation.slugLength')
    .max(80, 'support.editor.validation.slugLength')
    .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'support.editor.validation.slugPattern'),
  groupId: z.string().min(1, 'support.editor.validation.group'),
  // Disabled for an archived article, where the form reads no value.
  featuredPosition: z.enum(['', ...FEATURED_POSITIONS]).optional(),
  body: z
    .string()
    .max(20000, 'support.editor.validation.body')
    .refine((body) => body.trim().length > 0, 'support.editor.validation.body'),
});

type ArticleFormValues = z.infer<typeof articleSchema>;
type Field = FieldPath<ArticleFormValues>;

const EMPTY_FORM: ArticleFormValues = {
  title: '',
  slug: '',
  groupId: '',
  featuredPosition: '',
  body: '',
};

function toForm(article: AdminSupportKbArticle): ArticleFormValues {
  return {
    title: article.title,
    slug: article.slug,
    groupId: article.groupId,
    featuredPosition: article.featuredPosition
      ? (String(article.featuredPosition) as ArticleFormValues['featuredPosition'])
      : '',
    body: article.body,
  };
}

/** An archived article never sends a position: the server refuses one with 400. */
function toRequest(values: ArticleFormValues, archived: boolean): AdminSupportKbArticleRequest {
  return {
    title: values.title,
    slug: values.slug,
    groupId: values.groupId,
    body: values.body,
    ...(values.featuredPosition && !archived
      ? { featuredPosition: Number(values.featuredPosition) }
      : {}),
  };
}

const FIELD_PROBLEMS: Partial<Record<string, Field>> = {
  'slug-taken': 'slug',
  'featured-full': 'featuredPosition',
  'featured-position-taken': 'featuredPosition',
  'group-not-found': 'groupId',
};

const VALIDATION_MESSAGES: Record<Field, string> = {
  title: 'support.editor.validation.title',
  slug: 'support.editor.validation.slugPattern',
  groupId: 'support.editor.validation.group',
  featuredPosition: 'support.editor.fields.featuredArchived',
  body: 'support.editor.validation.body',
};

/** The locale key for the problem codes that have their own message. */
function problemMessage(code: string): string | undefined {
  switch (code) {
    case 'slug-taken':
    case 'featured-full':
    case 'featured-position-taken':
    case 'article-not-found':
      return `support.problems.${code}`;
    case 'group-not-found':
      return 'support.editor.validation.group';
    default:
      return undefined;
  }
}

export function ArticleEditorPage() {
  const { t } = useTranslation();
  const { articleId = NEW_ARTICLE } = useParams();
  const id = articleId === NEW_ARTICLE ? undefined : articleId;
  usePageTitle(t(id ? 'support.editor.editTitle' : 'support.editor.createTitle'));
  const articleQuery = useArticleQuery(id);
  const groupsQuery = useGroupsQuery();

  const notFound = supportProblemCode(articleQuery.error) === 'article-not-found';
  const missing = (id !== undefined && !articleQuery.data) || !groupsQuery.data;
  const failed = Boolean(articleQuery.error || groupsQuery.error);

  // A failed background refetch keeps the form, and the text typed into it, on screen.
  let content;
  if (notFound || (failed && missing)) {
    content = (
      <div className="cortex-admin-error">
        <p role="alert">
          {notFound ? t('support.editor.notFound') : t('support.common.loadFailed')}
        </p>
        {!notFound && (
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => {
              void articleQuery.refetch();
              void groupsQuery.refetch();
            }}
          >
            {t('support.common.retry')}
          </button>
        )}
      </div>
    );
  } else if (missing || !groupsQuery.data) {
    content = <output>{t('support.common.loading')}</output>;
  } else if (groupsQuery.data.length === 0) {
    content = (
      <div className="cortex-admin-empty">
        <p>{t('support.editor.noGroups')}</p>
        <Link to="/support/groups">{t('support.editor.toGroups')}</Link>
      </div>
    );
  } else {
    content = (
      <>
        {failed && <p className="cortex-admin-stale">{t('support.common.refreshFailed')}</p>}
        <ArticleForm
          key={id ?? NEW_ARTICLE}
          id={id}
          article={articleQuery.data}
          groups={groupsQuery.data}
        />
      </>
    );
  }

  return (
    <main>
      <Link className="cortex-admin-back-link" to="/support/articles">
        {t('support.editor.backToList')}
      </Link>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.articles.eyebrow')}</p>
          <h1>{t(id ? 'support.editor.editTitle' : 'support.editor.createTitle')}</h1>
        </div>
      </div>
      {content}
    </main>
  );
}

function ArticleForm({
  id,
  article,
  groups,
}: Readonly<{
  id: string | undefined;
  article: AdminSupportKbArticle | undefined;
  groups: AdminSupportKbGroup[];
}>) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const formId = useId();
  const save = useSaveArticleMutation(id);
  const refreshArticle = useRefreshArticle();
  const refreshGroups = useRefreshGroups();
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const {
    register,
    handleSubmit,
    reset,
    resetField,
    setError,
    watch,
    formState: { errors, isDirty },
  } = useForm<ArticleFormValues>({
    resolver: zodResolver(articleSchema),
    defaultValues: article ? toForm(article) : EMPTY_FORM,
  });
  const archived = article?.status === 'archived';
  const fieldId = (field: Field) => `${formId}-${field}`;
  const errorId = (field: Field) => `${formId}-${field}-error`;

  // Archiving frees the position on the server; only that field follows, typed text stays.
  useEffect(() => {
    if (archived) {
      resetField('featuredPosition', { defaultValue: '' });
    }
  }, [archived, resetField]);

  const onSubmit = handleSubmit((values) => {
    if (save.isPending) return;
    setFormError(null);
    setSaved(false);
    save.mutate(toRequest(values, archived), {
      onSuccess: (result) => {
        if (id === undefined) {
          navigate(`/support/articles/${result.id}`, { replace: true });
          return;
        }
        reset(toForm(result));
        setSaved(true);
      },
      onError: (error) => {
        if (id !== undefined) {
          void refreshArticle(id);
        }
        const code = supportProblemCode(error);
        if (code === 'group-not-found') {
          void refreshGroups();
        }
        const field = code ? FIELD_PROBLEMS[code] : undefined;
        const message = code ? problemMessage(code) : undefined;
        if (field && message) {
          setError(field, { type: 'server', message }, { shouldFocus: true });
          return;
        }
        const fieldErrors = error instanceof ApiError ? error.problem?.errors : undefined;
        const known = Object.keys(fieldErrors ?? {}).filter(
          (name): name is Field => name in VALIDATION_MESSAGES,
        );
        if (code === 'validation-failed' && known.length > 0) {
          known.forEach((name) =>
            setError(name, { type: 'server', message: VALIDATION_MESSAGES[name] }),
          );
          return;
        }
        setFormError(message ?? 'support.common.saveFailed');
      },
    });
  });

  const fieldProps = (field: Field, hint?: string) => ({
    id: fieldId(field),
    'aria-invalid': errors[field] ? true : undefined,
    'aria-describedby':
      [errors[field] ? errorId(field) : undefined, hint].filter(Boolean).join(' ') || undefined,
  });
  const fieldError = (field: Field) =>
    errors[field] && (
      <p id={errorId(field)} className="cortex-admin-form-error" role="alert">
        {t(errors[field]?.message ?? '')}
      </p>
    );
  const slugHintId = `${formId}-slug-hint`;
  const featuredHintId = `${formId}-featured-hint`;

  return (
    <div className="support-editor">
      <form className="support-editor-form" onSubmit={onSubmit} noValidate>
        {article && (
          <ArticleStatus
            article={article}
            dirty={isDirty}
            onDone={(result) => {
              reset(toForm(result));
              setSaved(false);
            }}
          />
        )}
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('title')}>{t('support.editor.fields.title')}</label>
          <input {...fieldProps('title')} {...register('title')} />
          {fieldError('title')}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('slug')}>{t('support.editor.fields.slug')}</label>
          <input {...fieldProps('slug', slugHintId)} autoComplete="off" {...register('slug')} />
          <small id={slugHintId} className="support-hint">
            {t('support.editor.fields.slugHint')}
          </small>
          {fieldError('slug')}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('groupId')}>{t('support.editor.fields.group')}</label>
          <select {...fieldProps('groupId')} {...register('groupId')}>
            <option value="">{t('support.editor.fields.groupPlaceholder')}</option>
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
          {fieldError('groupId')}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('featuredPosition')}>{t('support.editor.fields.featured')}</label>
          <select
            {...fieldProps('featuredPosition', archived ? featuredHintId : undefined)}
            disabled={archived}
            {...register('featuredPosition')}
          >
            <option value="">{t('support.editor.fields.featuredNone')}</option>
            {FEATURED_POSITIONS.map((position) => (
              <option key={position} value={position}>
                {t('support.editor.fields.featuredOption', { position })}
              </option>
            ))}
          </select>
          {archived && (
            <small id={featuredHintId} className="support-hint">
              {t('support.editor.fields.featuredArchived')}
            </small>
          )}
          {fieldError('featuredPosition')}
        </div>
        <div className="cortex-admin-field support-body-field">
          <label htmlFor={fieldId('body')}>{t('support.editor.fields.body')}</label>
          <textarea rows={18} {...fieldProps('body')} {...register('body')} />
          {fieldError('body')}
        </div>
        {formError && <p role="alert">{t(formError)}</p>}
        <div className="support-actions">
          <button type="submit" className="cortex-admin-btn cortex-admin-btn--primary" aria-disabled={save.isPending}>
            {save.isPending
              ? t('support.common.saving')
              : id
                ? t('support.common.save')
                : t('support.editor.create')}
          </button>
          {saved && !isDirty && (
            <output className="cortex-admin-form-success">{t('support.editor.saved')}</output>
          )}
        </div>
      </form>
      <Preview body={watch('body')} />
    </div>
  );
}

function ArticleStatus({
  article,
  dirty,
  onDone,
}: Readonly<{
  article: AdminSupportKbArticle;
  dirty: boolean;
  onDone: (result: AdminSupportKbArticle) => void;
}>) {
  const { t } = useTranslation();
  const change = useArticleStatusMutation(article.id);
  const refreshArticle = useRefreshArticle();
  const noteId = useId();
  const actionsRef = useRef<HTMLDivElement>(null);
  const [announcement, setAnnouncement] = useState('');
  const [refocus, setRefocus] = useState(false);
  const blocked = change.isPending || dirty;

  // The clicked button is replaced by the new status's actions; focus moves to the first of them.
  useEffect(() => {
    if (refocus) {
      actionsRef.current?.querySelector('button')?.focus();
      setRefocus(false);
    }
  }, [refocus, article.status]);
  const actions: { action: 'publish' | 'archive'; label: string }[] = {
    draft: [
      { action: 'publish' as const, label: t('support.editor.publish') },
      { action: 'archive' as const, label: t('support.editor.archive') },
    ],
    published: [{ action: 'archive' as const, label: t('support.editor.archive') }],
    archived: [{ action: 'publish' as const, label: t('support.editor.republish') }],
  }[article.status];
  const code = supportProblemCode(change.error);

  return (
    <div className="support-status">
      <p>
        <strong>
          {t('support.editor.status', { label: t(`support.articleStatus.${article.status}`) })}
        </strong>
      </p>
      <div className="support-actions" ref={actionsRef}>
        {actions.map(({ action, label }) => (
          <button
            key={action}
            type="button"
            className={
              action === 'archive'
                ? 'cortex-admin-btn cortex-admin-btn--danger'
                : 'cortex-admin-btn'
            }
            aria-disabled={blocked}
            aria-describedby={dirty ? noteId : undefined}
            onClick={() => {
              if (blocked) return;
              setAnnouncement('');
              change.mutate(action, {
                onSuccess: (result) => {
                  onDone(result);
                  setAnnouncement(
                    t('support.editor.statusChanged', {
                      label: t(`support.articleStatus.${result.status}`),
                    }),
                  );
                  setRefocus(true);
                },
                onError: () => void refreshArticle(article.id),
              });
            }}
          >
            {change.isPending && change.variables === action ? t('support.editor.working') : label}
          </button>
        ))}
      </div>
      {dirty && (
        <small id={noteId} className="support-hint">
          {t('support.editor.saveBeforeStatus')}
        </small>
      )}
      <p role="status" className="support-announcement">
        {announcement}
      </p>
      {change.error && (
        <p role="alert">
          {code === 'article-not-found'
            ? t('support.problems.article-not-found')
            : t('support.common.saveFailed')}
        </p>
      )}
    </div>
  );
}

function Preview({ body }: Readonly<{ body: string }>) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <section className="support-preview" aria-labelledby={headingId}>
      <h2 id={headingId}>{t('support.editor.previewHeading')}</h2>
      {body.trim() ? (
        <Markdown>{body}</Markdown>
      ) : (
        <p className="support-hint">{t('support.editor.previewEmpty')}</p>
      )}
    </section>
  );
}
