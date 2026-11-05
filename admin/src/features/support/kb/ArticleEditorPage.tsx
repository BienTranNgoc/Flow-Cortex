import { zodResolver } from '@hookform/resolvers/zod';
import {
  ApiError,
  type AdminSupportKbArticle,
  type AdminSupportKbArticleRequest,
  type AdminSupportKbGroup,
} from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useForm, type FieldPath } from 'react-hook-form';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { usePageTitle } from '../../../app/usePageTitle';
import { Markdown } from '../Markdown';
import { supportCopy } from '../supportCopy';
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

const copy = supportCopy.editor;
const NEW_ARTICLE = 'new';
const FEATURED_POSITIONS = ['1', '2', '3'] as const;

const articleSchema = z.object({
  title: z.string().trim().min(1, copy.validation.title).max(150, copy.validation.title),
  slug: z
    .string()
    .min(1, copy.validation.slugLength)
    .max(80, copy.validation.slugLength)
    .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, copy.validation.slugPattern),
  groupId: z.string().min(1, copy.validation.group),
  // Disabled for an archived article, where the form reads no value.
  featuredPosition: z.enum(['', ...FEATURED_POSITIONS]).optional(),
  body: z
    .string()
    .max(20000, copy.validation.body)
    .refine((body) => body.trim().length > 0, copy.validation.body),
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
  title: copy.validation.title,
  slug: copy.validation.slugPattern,
  groupId: copy.validation.group,
  featuredPosition: copy.fields.featuredArchived,
  body: copy.validation.body,
};

/** The problem codes whose message has its own entry in the copy. */
function problemMessage(code: string): string | undefined {
  switch (code) {
    case 'slug-taken':
    case 'featured-full':
    case 'featured-position-taken':
    case 'article-not-found':
      return supportCopy.problems[code];
    case 'group-not-found':
      return copy.validation.group;
    default:
      return undefined;
  }
}

export function ArticleEditorPage() {
  const { articleId = NEW_ARTICLE } = useParams();
  const id = articleId === NEW_ARTICLE ? undefined : articleId;
  usePageTitle(id ? copy.editTitle : copy.createTitle);
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
        <p role="alert">{notFound ? copy.notFound : supportCopy.common.loadFailed}</p>
        {!notFound && (
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => {
              void articleQuery.refetch();
              void groupsQuery.refetch();
            }}
          >
            {supportCopy.common.retry}
          </button>
        )}
      </div>
    );
  } else if (missing || !groupsQuery.data) {
    content = <output>{supportCopy.common.loading}</output>;
  } else if (groupsQuery.data.length === 0) {
    content = (
      <div className="cortex-admin-empty">
        <p>{copy.noGroups}</p>
        <Link to="/support/groups">{copy.toGroups}</Link>
      </div>
    );
  } else {
    content = (
      <>
        {failed && <p className="cortex-admin-stale">{supportCopy.common.refreshFailed}</p>}
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
        {copy.backToList}
      </Link>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{supportCopy.articles.eyebrow}</p>
          <h1>{id ? copy.editTitle : copy.createTitle}</h1>
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
        setFormError(message ?? supportCopy.common.saveFailed);
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
      <p id={errorId(field)} role="alert">
        {errors[field]?.message}
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
          <label htmlFor={fieldId('title')}>{copy.fields.title}</label>
          <input {...fieldProps('title')} {...register('title')} />
          {fieldError('title')}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('slug')}>{copy.fields.slug}</label>
          <input {...fieldProps('slug', slugHintId)} autoComplete="off" {...register('slug')} />
          <small id={slugHintId} className="support-hint">
            {copy.fields.slugHint}
          </small>
          {fieldError('slug')}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('groupId')}>{copy.fields.group}</label>
          <select {...fieldProps('groupId')} {...register('groupId')}>
            <option value="">{copy.fields.groupPlaceholder}</option>
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
          {fieldError('groupId')}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={fieldId('featuredPosition')}>{copy.fields.featured}</label>
          <select
            {...fieldProps('featuredPosition', archived ? featuredHintId : undefined)}
            disabled={archived}
            {...register('featuredPosition')}
          >
            <option value="">{copy.fields.featuredNone}</option>
            {FEATURED_POSITIONS.map((position) => (
              <option key={position} value={position}>
                {copy.fields.featuredOption(Number(position))}
              </option>
            ))}
          </select>
          {archived && (
            <small id={featuredHintId} className="support-hint">
              {copy.fields.featuredArchived}
            </small>
          )}
          {fieldError('featuredPosition')}
        </div>
        <div className="cortex-admin-field support-body-field">
          <label htmlFor={fieldId('body')}>{copy.fields.body}</label>
          <textarea rows={18} {...fieldProps('body')} {...register('body')} />
          {fieldError('body')}
        </div>
        {formError && <p role="alert">{formError}</p>}
        <div className="support-actions">
          <button type="submit" className="cortex-admin-btn cortex-admin-btn--primary" aria-disabled={save.isPending}>
            {save.isPending
              ? supportCopy.common.saving
              : id
                ? supportCopy.common.save
                : copy.create}
          </button>
          {saved && !isDirty && <output className="cortex-admin-form-success">{copy.saved}</output>}
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
      { action: 'publish' as const, label: copy.publish },
      { action: 'archive' as const, label: copy.archive },
    ],
    published: [{ action: 'archive' as const, label: copy.archive }],
    archived: [{ action: 'publish' as const, label: copy.republish }],
  }[article.status];
  const code = supportProblemCode(change.error);

  return (
    <div className="support-status">
      <p>
        <strong>{copy.status(supportCopy.articleStatus[article.status])}</strong>
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
                  setAnnouncement(copy.statusChanged(supportCopy.articleStatus[result.status]));
                  setRefocus(true);
                },
                onError: () => void refreshArticle(article.id),
              });
            }}
          >
            {change.isPending && change.variables === action ? copy.working : label}
          </button>
        ))}
      </div>
      {dirty && (
        <small id={noteId} className="support-hint">
          {copy.saveBeforeStatus}
        </small>
      )}
      <p role="status" className="support-announcement">
        {announcement}
      </p>
      {change.error && (
        <p role="alert">
          {code === 'article-not-found'
            ? supportCopy.problems['article-not-found']
            : supportCopy.common.saveFailed}
        </p>
      )}
    </div>
  );
}

function Preview({ body }: Readonly<{ body: string }>) {
  const headingId = useId();
  return (
    <section className="support-preview" aria-labelledby={headingId}>
      <h2 id={headingId}>{copy.previewHeading}</h2>
      {body.trim() ? (
        <Markdown>{body}</Markdown>
      ) : (
        <p className="support-hint">{copy.previewEmpty}</p>
      )}
    </section>
  );
}
