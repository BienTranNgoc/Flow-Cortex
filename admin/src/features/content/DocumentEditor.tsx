import type {
  ContentDocumentDraft,
  ContentDocumentSummary,
  ContentMediaItem,
} from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { can, type Caller } from '../../app/permissions';
import { conflictResolution, type Versioned } from './conflictResolution';
import { contentProblem } from './contentProblems';
import { ConfirmDialog, ConflictDialog } from './EditorDialogs';
import { describePath, errorAt, firstLocale, isStillBlank, seoPath } from './fieldPaths';
import { DocumentPreview } from './DocumentPreview';
import {
  LOCALES,
  MAX_SECTIONS,
  MAX_SEO_DESCRIPTION_CHARS,
  MAX_SEO_TITLE_CHARS,
  fromWire,
  isEditable,
  mediaIdOf,
  moved,
  newSection,
  withMedia,
  type ContentLocale,
  type DocumentContent,
  type Section,
} from './documentModel';
import { MediaPicker } from './MediaPicker';
import { publishGate } from './publishGate';
import { RevisionsPanel } from './RevisionsPanel';
import { saveGate } from './saveGate';
import { SectionEditor } from './SectionEditor';
import { TextField } from './TextField';
import { useUnsavedGuard } from './useUnsavedGuard';
import {
  readRevision,
  useLatestDraft,
  usePublishMutation,
  useSaveDraftMutation,
} from './useContentDocuments';

interface EditorState {
  content: DocumentContent;
  version: number;
  unsaved: boolean;
}

type Dialog =
  | { kind: 'publish'; version: number }
  | { kind: 'conflict'; currentVersion: number | undefined; intent: 'save' | 'publish' }
  | { kind: 'replace'; snapshot: Versioned<DocumentContent> };

const sameContent = (a: DocumentContent, b: DocumentContent) =>
  JSON.stringify(a) === JSON.stringify(b);

function pathLabel(t: TFunction, path: string): string {
  if (path === 'sections') return t('content.editor.sections');
  if (path === 'seoMeta') return t('content.editor.seo');
  const described = describePath(path);
  if (!described) return path;
  const field = t(`content.fields.${described.field}`);
  const base =
    described.section === undefined
      ? field
      : t('content.editor.pathSection', { n: described.section + 1, field });
  return described.locale
    ? t('content.editor.pathLocale', {
        label: base,
        language: t(`content.languages.${described.locale}`),
      })
    : base;
}

interface DocumentEditorProps {
  readonly slug: string;
  readonly summary: ContentDocumentSummary;
  readonly draft: ContentDocumentDraft;
  readonly me: Caller | undefined;
}

/**
 * Holds the document being edited. The server's draft only seeds it: after that content changes
 * through the editor's own edits, a save, or an explicit conflict or revision choice.
 */
export function DocumentEditor({ slug, summary, draft, me }: DocumentEditorProps) {
  const { t, i18n } = useTranslation();
  const ids = useId();
  const tabId = (locale: ContentLocale) => `${ids}-tab-${locale}`;
  const panelId = `${ids}-panel`;
  const hintId = `${ids}-publish-hint`;
  const tabs = useRef<Record<string, HTMLButtonElement | null>>({});

  const canManage = can(me, 'content.document.manage');
  const readOnly = !canManage;
  const canBrowseMedia = can(me, 'content.media.view');
  const allowedTypes = summary.sectionTypes.filter(isEditable);

  const [state, setState] = useState<EditorState>(() => ({
    content: fromWire(draft),
    version: draft.version,
    unsaved: false,
  }));
  const [locale, setLocale] = useState<ContentLocale>(i18n.language.startsWith('vi') ? 'vi' : 'en');
  const [mode, setMode] = useState<'edit' | 'preview'>('edit');
  const [showRevisions, setShowRevisions] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [incomplete, setIncomplete] = useState<string[]>([]);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [dialogBusy, setDialogBusy] = useState(false);
  const [picking, setPicking] = useState<number | null>(null);
  const [mediaItems, setMediaItems] = useState<Record<string, ContentMediaItem>>({});
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [addType, setAddType] = useState(allowedTypes[0] ?? '');

  const save = useSaveDraftMutation(slug);
  const publish = usePublishMutation(slug);
  const fetchLatest = useLatestDraft(slug);
  const guard = useUnsavedGuard(state.unsaved && canManage);
  const gate = publishGate({ me, unsaved: state.unsaved, version: state.version });
  const saveEnabled = saveGate({
    canManage,
    pending: save.isPending,
    unsaved: state.unsaved,
    version: state.version,
  });

  const clearFeedback = () => {
    setErrors({});
    setIncomplete([]);
    setFailure(null);
  };

  const edit = (update: (content: DocumentContent) => DocumentContent, clearedPath?: string) => {
    setState((current) => ({ ...current, content: update(current.content), unsaved: true }));
    setNotice(null);
    if (clearedPath === undefined) {
      setErrors({});
      setIncomplete([]);
    } else {
      setErrors((current) =>
        Object.fromEntries(Object.entries(current).filter(([path]) => path !== clearedPath)),
      );
    }
  };

  const editSection = (index: number, section: Section, path: string) =>
    edit(
      (content) => ({
        ...content,
        sections: content.sections.map((existing, i) => (i === index ? section : existing)),
      }),
      path,
    );

  const showLocaleOf = (paths: readonly string[]) => {
    const target = firstLocale(paths);
    if (target) setLocale(target);
    setMode('edit');
  };

  const submit = (version: number, content: DocumentContent) => {
    setFailure(null);
    setNotice(null);
    save.mutate(
      { version, content },
      {
        onSuccess: (saved) => {
          setState((current) => ({
            content: current.content,
            version: saved.version,
            unsaved: !sameContent(current.content, content),
          }));
          setErrors({});
          setNotice(t('content.editor.saved', { version: saved.version }));
        },
        onError: (error) => {
          const problem = contentProblem(error);
          if (problem?.code === 'version-conflict') {
            setDialog({ kind: 'conflict', currentVersion: problem.currentVersion, intent: 'save' });
          } else if (problem && Object.keys(problem.errors).length > 0) {
            setErrors(problem.errors);
            showLocaleOf(Object.keys(problem.errors));
          } else {
            setFailure(problem?.detail ?? t('content.editor.saveFailed'));
          }
        },
      },
    );
  };

  const onSave = () => {
    if (!saveEnabled) return;
    submit(state.version, state.content);
  };

  const confirmPublish = (version: number) => {
    setDialogError(null);
    publish.mutate(version, {
      onSuccess: () => {
        setDialog(null);
        setIncomplete([]);
        setNotice(t('content.editor.published', { version }));
      },
      onError: (error) => {
        const problem = contentProblem(error);
        if (problem?.code === 'document-incomplete') {
          setDialog(null);
          setIncomplete(problem.fields);
          showLocaleOf(problem.fields);
        } else if (problem?.code === 'version-conflict') {
          setDialog({
            kind: 'conflict',
            currentVersion: problem.currentVersion,
            intent: 'publish',
          });
        } else {
          setDialogError(problem?.detail ?? t('content.editor.publishFailed'));
        }
      },
    });
  };

  const loadLatest = async () => {
    setDialogBusy(true);
    setDialogError(null);
    try {
      const latest = await fetchLatest();
      const next = conflictResolution(
        'load-latest',
        { content: state.content, version: state.version },
        { content: fromWire(latest), version: latest.version },
      );
      setState(next);
      clearFeedback();
      setDialog(null);
      setNotice(t('content.conflict.loaded', { version: latest.version }));
    } catch {
      setDialogError(t('content.conflict.loadFailed'));
    } finally {
      setDialogBusy(false);
    }
  };

  const overwrite = async (known: number | undefined) => {
    setDialogBusy(true);
    setDialogError(null);
    try {
      const version = known ?? (await fetchLatest()).version;
      const next = conflictResolution(
        'overwrite',
        { content: state.content, version: state.version },
        { content: state.content, version },
      );
      setState(next);
      setDialog(null);
      submit(next.version, next.content);
    } catch {
      setDialogError(t('content.conflict.loadFailed'));
    } finally {
      setDialogBusy(false);
    }
  };

  const applyRevision = (snapshot: Versioned<DocumentContent>) => {
    setState(
      conflictResolution(
        'load-revision',
        { content: state.content, version: state.version },
        snapshot,
      ),
    );
    clearFeedback();
    setMode('edit');
    setDialog(null);
    setNotice(t('content.revisions.loaded', { version: snapshot.version }));
  };

  const loadRevision = async (id: string) => {
    setLoadingId(id);
    setFailure(null);
    try {
      const revision = await readRevision(slug, id);
      const snapshot = { content: fromWire(revision), version: revision.version };
      if (state.unsaved) setDialog({ kind: 'replace', snapshot });
      else applyRevision(snapshot);
    } catch {
      setFailure(t('content.revisions.loadRevisionFailed'));
    } finally {
      setLoadingId(null);
    }
  };

  const onTabKey = (event: KeyboardEvent) => {
    const keys: Record<string, ContentLocale | undefined> = {
      ArrowLeft: LOCALES[0],
      Home: LOCALES[0],
      ArrowRight: LOCALES[1],
      End: LOCALES[1],
    };
    const target = keys[event.key];
    if (!target) return;
    event.preventDefault();
    setLocale(target);
    tabs.current[target]?.focus();
  };

  const stateText = state.unsaved
    ? t('content.editor.unsaved')
    : state.version > 0
      ? t('content.editor.draftVersion', { version: state.version })
      : t('content.editor.noDraft');
  const remainingIncomplete = incomplete.filter((path) => isStillBlank(state.content, path));
  const errorEntries = Object.entries(errors);
  const sectionCount = state.content.sections.length;
  const atLimit = sectionCount >= MAX_SECTIONS;

  return (
    <div className="content-editor">
      <div className="content-editor__bar">
        <div role="tablist" aria-label={t('content.editor.languageTabs')} className="content-tabs">
          {LOCALES.map((tab) => (
            <button
              key={tab}
              ref={(node) => {
                tabs.current[tab] = node;
              }}
              type="button"
              role="tab"
              id={tabId(tab)}
              aria-selected={locale === tab}
              aria-controls={panelId}
              tabIndex={locale === tab ? 0 : -1}
              className="content-tab"
              onClick={() => setLocale(tab)}
              onKeyDown={onTabKey}
            >
              {t(`content.languages.${tab}`)}
            </button>
          ))}
        </div>
        <fieldset aria-label={t('content.editor.modeLabel')} className="content-modes">
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--sm"
            aria-pressed={mode === 'edit'}
            onClick={() => setMode('edit')}
          >
            {t('content.editor.edit')}
          </button>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--sm"
            aria-pressed={mode === 'preview'}
            onClick={() => setMode('preview')}
          >
            {t('content.editor.preview')}
          </button>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--sm"
            aria-expanded={showRevisions}
            onClick={() => setShowRevisions((open) => !open)}
          >
            {t('content.editor.revisions')}
          </button>
        </fieldset>
        <output className="content-editor__status">
          {stateText}
          {notice && <span className="content-editor__notice"> · {notice}</span>}
        </output>
        {canManage && (
          <div className="content-editor__actions">
            <button
              type="button"
              className="cortex-admin-btn"
              aria-disabled={!saveEnabled}
              onClick={onSave}
            >
              {save.isPending ? t('content.editor.saving') : t('content.editor.save')}
            </button>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--primary"
              aria-disabled={!gate.enabled || publish.isPending}
              aria-describedby={gate.reason ? hintId : undefined}
              onClick={() => {
                if (gate.enabled && !publish.isPending) {
                  setDialogError(null);
                  setDialog({ kind: 'publish', version: state.version });
                }
              }}
            >
              {t('content.editor.publish')}
            </button>
            {gate.reason && (
              <small id={hintId} className="content-hint">
                {t(`content.editor.publishHint.${gate.reason}`)}
              </small>
            )}
          </div>
        )}
      </div>

      <div
        className="content-editor__body"
        role="tabpanel"
        id={panelId}
        aria-labelledby={tabId(locale)}
        tabIndex={-1}
      >
        {readOnly && <p className="content-hint">{t('content.editor.readOnly')}</p>}
        {failure && (
          <p className="cortex-admin-form-error" role="alert">
            {failure}
          </p>
        )}
        {errorEntries.length > 0 && (
          <div className="cortex-admin-error" role="alert">
            <p>{t('content.editor.invalidSummary')}</p>
            <ul>
              {errorEntries.map(([path, message]) => (
                <li key={path}>
                  {pathLabel(t, path)}: {message}
                </li>
              ))}
            </ul>
          </div>
        )}
        {remainingIncomplete.length > 0 && (
          <div className="cortex-admin-error" role="alert">
            <p>{t('content.editor.incompleteSummary')}</p>
            <ul>
              {remainingIncomplete.map((path) => (
                <li key={path}>{pathLabel(t, path)}</li>
              ))}
            </ul>
          </div>
        )}

        {showRevisions && (
          <RevisionsPanel
            slug={slug}
            canLoad={canManage}
            loadingId={loadingId}
            onLoad={(id) => void loadRevision(id)}
          />
        )}

        {mode === 'preview' ? (
          <DocumentPreview content={state.content} locale={locale} mediaItems={mediaItems} />
        ) : (
          <>
            <fieldset className="content-seo">
              <legend>{t('content.editor.seo')}</legend>
              <TextField
                label={t('content.editor.fieldIn', {
                  field: t('content.fields.seoTitle'),
                  language: t(`content.languages.${locale}`),
                })}
                value={state.content.seoMeta.title[locale]}
                locale={locale}
                max={MAX_SEO_TITLE_CHARS}
                readOnly={readOnly}
                error={errorAt(errors, seoPath('title', locale))}
                onChange={(value) =>
                  edit(
                    (content) => ({
                      ...content,
                      seoMeta: {
                        ...content.seoMeta,
                        title: { ...content.seoMeta.title, [locale]: value },
                      },
                    }),
                    seoPath('title', locale),
                  )
                }
              />
              <TextField
                label={t('content.editor.fieldIn', {
                  field: t('content.fields.seoDescription'),
                  language: t(`content.languages.${locale}`),
                })}
                value={state.content.seoMeta.description[locale]}
                locale={locale}
                max={MAX_SEO_DESCRIPTION_CHARS}
                rows={3}
                readOnly={readOnly}
                error={errorAt(errors, seoPath('description', locale))}
                onChange={(value) =>
                  edit(
                    (content) => ({
                      ...content,
                      seoMeta: {
                        ...content.seoMeta,
                        description: { ...content.seoMeta.description, [locale]: value },
                      },
                    }),
                    seoPath('description', locale),
                  )
                }
              />
            </fieldset>

            {errors.sections && (
              <p className="cortex-admin-form-error" role="alert">
                {errors.sections}
              </p>
            )}
            {sectionCount === 0 && <p className="content-hint">{t('content.editor.noSections')}</p>}
            <ol className="content-sections" aria-label={t('content.editor.sections')}>
              {state.content.sections.map((section, index) => (
                <li key={index} className="content-section">
                  <div className="content-section__header">
                    <h3>
                      {t('content.editor.sectionTitle', {
                        n: index + 1,
                        type: t(`content.sectionTypes.${section.type}`, {
                          defaultValue: section.type,
                        }),
                      })}
                    </h3>
                    {!readOnly && (
                      <div className="content-section__controls">
                        <button
                          type="button"
                          className="cortex-admin-btn cortex-admin-btn--sm"
                          aria-label={t('content.editor.moveUp', { n: index + 1 })}
                          aria-disabled={index === 0}
                          onClick={() =>
                            index > 0 &&
                            edit((content) => ({
                              ...content,
                              sections: moved(content.sections, index, -1),
                            }))
                          }
                        >
                          {t('content.editor.up')}
                        </button>
                        <button
                          type="button"
                          className="cortex-admin-btn cortex-admin-btn--sm"
                          aria-label={t('content.editor.moveDown', { n: index + 1 })}
                          aria-disabled={index === sectionCount - 1}
                          onClick={() =>
                            index < sectionCount - 1 &&
                            edit((content) => ({
                              ...content,
                              sections: moved(content.sections, index, 1),
                            }))
                          }
                        >
                          {t('content.editor.down')}
                        </button>
                        <button
                          type="button"
                          className="cortex-admin-btn cortex-admin-btn--sm cortex-admin-btn--danger"
                          aria-label={t('content.editor.remove', { n: index + 1 })}
                          onClick={() =>
                            edit((content) => ({
                              ...content,
                              sections: content.sections.filter((_, i) => i !== index),
                            }))
                          }
                        >
                          {t('content.editor.removeShort')}
                        </button>
                      </div>
                    )}
                  </div>
                  <SectionEditor
                    section={section}
                    index={index}
                    locale={locale}
                    readOnly={readOnly}
                    errors={errors}
                    canBrowseMedia={canBrowseMedia}
                    mediaItem={mediaItems[mediaIdOf(section) ?? '']}
                    onEdit={(next, path) => editSection(index, next, path)}
                    onPickMedia={() => setPicking(index)}
                  />
                </li>
              ))}
            </ol>
            {!readOnly && (
              <div className="content-add">
                {allowedTypes.length > 0 ? (
                  <>
                    <div className="cortex-admin-field">
                      <label htmlFor={`${ids}-add`}>{t('content.editor.addType')}</label>
                      <select
                        id={`${ids}-add`}
                        value={addType}
                        onChange={(event) => setAddType(event.target.value)}
                      >
                        {allowedTypes.map((type) => (
                          <option key={type} value={type}>
                            {t(`content.sectionTypes.${type}`, { defaultValue: type })}
                          </option>
                        ))}
                      </select>
                    </div>
                    <button
                      type="button"
                      className="cortex-admin-btn"
                      aria-disabled={atLimit}
                      onClick={() =>
                        !atLimit &&
                        edit((content) => ({
                          ...content,
                          sections: [...content.sections, newSection(addType)],
                        }))
                      }
                    >
                      {t('content.editor.addSection')}
                    </button>
                    {atLimit && (
                      <small className="content-hint">
                        {t('content.editor.sectionLimit', { max: MAX_SECTIONS })}
                      </small>
                    )}
                  </>
                ) : (
                  <p className="content-hint">{t('content.editor.noAddableTypes')}</p>
                )}
              </div>
            )}
          </>
        )}
      </div>

      {dialog?.kind === 'publish' && (
        <ConfirmDialog
          title={t('content.publish.title')}
          message={t('content.publish.message', { version: dialog.version })}
          confirmLabel={t('content.publish.confirm')}
          pending={publish.isPending}
          error={dialogError}
          onConfirm={() => confirmPublish(dialog.version)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'conflict' && (
        <ConflictDialog
          currentVersion={dialog.currentVersion}
          allowOverwrite={dialog.intent === 'save'}
          pending={dialogBusy}
          error={dialogError}
          onLoadLatest={() => void loadLatest()}
          onOverwrite={() => void overwrite(dialog.currentVersion)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'replace' && (
        <ConfirmDialog
          title={t('content.revisions.replaceTitle')}
          message={t('content.revisions.replaceMessage')}
          confirmLabel={t('content.revisions.replaceConfirm')}
          onConfirm={() => applyRevision(dialog.snapshot)}
          onClose={() => setDialog(null)}
        />
      )}
      {guard.pending !== null && (
        <ConfirmDialog
          title={t('content.leave.title')}
          message={t('content.leave.message')}
          confirmLabel={t('content.leave.confirm')}
          onConfirm={guard.leave}
          onClose={guard.stay}
        />
      )}
      {picking !== null && (
        <MediaPicker
          canUpload={can(me, 'content.media.manage')}
          onClose={() => setPicking(null)}
          onPick={(item) => {
            const index = picking;
            setMediaItems((current) => ({ ...current, [item.id]: item }));
            const target = state.content.sections[index];
            if (target)
              editSection(index, withMedia(target, item.id), `sections[${index}].data.mediaId`);
            setPicking(null);
          }}
        />
      )}
    </div>
  );
}
