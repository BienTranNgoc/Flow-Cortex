import { ApiError, type ContentMediaItem } from '@flow-cortex/api-client';
import { useId, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Pager, Timestamp } from '../../app/AdminList';
import { activeLocale } from '../../lib/i18n';
import { contentProblem } from './contentProblems';
import { formatBytes } from './formatBytes';
import { MediaThumb } from './MediaThumb';
import { useMediaQuery, useUploadMedia, type MediaFilter } from './useContentMedia';

const FILTERS: readonly MediaFilter[] = ['', 'image', 'video'];
const ACCEPT = '.jpg,.jpeg,.png,.webp,.mp4,.webm';

interface MediaBrowserProps {
  /** The caller holds `content.media.manage`: the upload control is absent otherwise. */
  readonly canUpload: boolean;
  /** Set in the picker: a card becomes a button that hands its item over. */
  readonly onChoose?: ((item: ContentMediaItem) => void) | undefined;
}

/** The library grid with its type filter, pager and upload control, shared by the Media library page and the picker. */
export function MediaBrowser({ canUpload, onChoose }: MediaBrowserProps) {
  const { t } = useTranslation();
  const filterId = useId();
  const fileId = useId();
  const refusalId = useId();
  const [filter, setFilter] = useState<MediaFilter>('');
  const [page, setPage] = useState(0);
  const [progress, setProgress] = useState<{ name: string; percent: number } | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const query = useMediaQuery(filter, page);
  const upload = useUploadMedia();
  const { data, error } = query;
  const locale = activeLocale();
  const disabled = error instanceof ApiError && error.status === 503;

  const refusalText = (failure: unknown) => {
    const problem = contentProblem(failure);
    return (
      Object.values(problem?.errors ?? {})[0] ?? problem?.detail ?? t('content.media.uploadFailed')
    );
  };

  const onFile = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || upload.isPending) return;
    setRefusal(null);
    setProgress({ name: file.name, percent: 0 });
    upload.mutate(
      { file, onProgress: (percent) => setProgress({ name: file.name, percent }) },
      {
        onSuccess: () => {
          setFilter('');
          setPage(0);
        },
        onError: (failure) => setRefusal(refusalText(failure)),
        onSettled: () => setProgress(null),
      },
    );
  };

  const filterLabel = (value: MediaFilter) => t(`content.media.filter.${value || 'all'}`);

  return (
    <div className="content-media-browser">
      <div className="cortex-admin-toolbar">
        <div className="cortex-admin-field">
          <label htmlFor={filterId}>{t('content.media.filter.label')}</label>
          <select
            id={filterId}
            value={filter}
            onChange={(event) => {
              setFilter(event.target.value as MediaFilter);
              setPage(0);
            }}
          >
            {FILTERS.map((value) => (
              <option key={value} value={value}>
                {filterLabel(value)}
              </option>
            ))}
          </select>
        </div>
        {canUpload && (
          <div className="cortex-admin-field">
            <label htmlFor={fileId}>{t('content.media.upload.label')}</label>
            <input
              id={fileId}
              type="file"
              accept={ACCEPT}
              disabled={upload.isPending}
              aria-describedby={refusal ? refusalId : undefined}
              onChange={onFile}
            />
          </div>
        )}
      </div>

      {progress && (
        <output className="content-upload-progress">
          <span>
            {t('content.media.upload.progress', { name: progress.name, percent: progress.percent })}
          </span>
          <progress
            max={100}
            value={progress.percent}
            aria-label={t('content.media.upload.progressLabel', { name: progress.name })}
          />
        </output>
      )}
      {refusal && (
        <p id={refusalId} className="cortex-admin-form-error" role="alert">
          {refusal}
        </p>
      )}

      {disabled && (
        <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
      )}
      {error && !disabled && (
        <div className="cortex-admin-error">
          <p role="alert">{t('content.media.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      )}
      {query.isPending && (
        <output className="cortex-admin-loading">{t('content.media.loading')}</output>
      )}
      {data && error && <p className="cortex-admin-stale">{t('common.shell.list.stale')}</p>}
      {data?.items.length === 0 && (
        <div className="cortex-admin-empty">
          <p>{filter ? t('content.media.emptyFiltered') : t('content.media.empty')}</p>
          {filter && (
            <button type="button" className="cortex-admin-btn" onClick={() => setFilter('')}>
              {t('common.shell.list.clearFilter')}
            </button>
          )}
        </div>
      )}
      {data && data.items.length > 0 && (
        <ul
          className="content-media-grid"
          aria-label={t('content.media.gridLabel')}
          aria-busy={query.isPlaceholderData}
        >
          {data.items.map((item) => {
            const thumb = <MediaThumb url={item.url} kind={item.kind} alt={item.fileName} />;
            return (
              <li key={item.id} className="content-media-card">
                {onChoose ? (
                  <button
                    type="button"
                    className="content-media-card__choose"
                    aria-label={t('content.media.choose', { name: item.fileName })}
                    onClick={() => onChoose(item)}
                  >
                    {thumb}
                  </button>
                ) : (
                  <div className="content-media-card__frame">{thumb}</div>
                )}
                <p className="content-media-card__name">{item.fileName}</p>
                <p className="content-media-card__meta">
                  {formatBytes(item.sizeBytes, locale)}
                  {item.uploaderEmail ? ` · ${item.uploaderEmail}` : ''}
                </p>
                <p className="content-media-card__meta">
                  <Timestamp value={item.createdAt} />
                </p>
              </li>
            );
          })}
        </ul>
      )}
      {data && (data.items.length > 0 || data.page > 0) && (
        <Pager
          page={data.page}
          size={data.size}
          total={data.total}
          nounKey="content.media.noun"
          onPageChange={setPage}
        />
      )}
    </div>
  );
}
