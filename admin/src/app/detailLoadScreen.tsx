import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import type { ReactNode } from 'react';

interface DetailRead {
  readonly error: unknown;
  readonly isPending: boolean;
  readonly isError: boolean;
  readonly data?: unknown;
  readonly refetch: () => unknown;
}

interface DetailLoadScreenOptions {
  /** The link back to the list, shown above every state. */
  readonly back: ReactNode;
  /** Prefix of the page's `title`, `notFound`, `notFoundHint`, `loading`, `forbidden` and `loadError` keys. */
  readonly keyPrefix: string;
  /** Whether the id in the address is well formed; a malformed one reads as not found without a request. */
  readonly idValid: boolean;
  readonly query: DetailRead;
}

/**
 * What a detail page shows until its record can be read: not found (a bad id, 400 or 404), the
 * console's kill switch (503), loading, or a failure with a retry. Null once the record is there.
 */
export function detailLoadScreen(
  t: TFunction,
  { back, keyPrefix, idValid, query }: DetailLoadScreenOptions,
): ReactNode {
  const failure = query.error instanceof ApiError ? query.error.status : undefined;
  if (!idValid || failure === 404 || failure === 400) {
    return (
      <div>
        {back}
        <h1>{t(`${keyPrefix}.notFound`)}</h1>
        <p role="alert">{t(`${keyPrefix}.notFoundHint`)}</p>
      </div>
    );
  }

  let state: ReactNode;
  if (failure === 503) {
    state = <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>;
  } else if (query.isPending) {
    state = <output className="cortex-admin-loading">{t(`${keyPrefix}.loading`)}</output>;
  } else if (query.isError || !query.data) {
    state = (
      <div className="cortex-admin-error">
        <p role="alert">{failure === 403 ? t(`${keyPrefix}.forbidden`) : t(`${keyPrefix}.loadError`)}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
          {t('common.shell.list.retry')}
        </button>
      </div>
    );
  } else {
    return null;
  }
  return (
    <div>
      {back}
      <h1>{t(`${keyPrefix}.title`)}</h1>
      {state}
    </div>
  );
}
