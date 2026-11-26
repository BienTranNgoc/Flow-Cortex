import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { ListFilters } from './useListFilters';

const SEARCH_MAX = 255;

interface ListSearchToolbarProps {
  readonly filters: ListFilters<string>;
  /** Prefix of the page's `search.label`, `search.placeholder`, `search.submit` and `search.clear` keys. */
  readonly keyPrefix: string;
  /** Page-owned controls placed after the search buttons. */
  readonly children?: ReactNode;
}

/** The name search above a paged admin list, with a Clear button while a search or a status filter is active. */
export function ListSearchToolbar({ filters, keyPrefix, children }: Readonly<ListSearchToolbarProps>) {
  const { t } = useTranslation();
  const searchId = useId();
  return (
    <form className="cortex-admin-toolbar" role="search" onSubmit={filters.search}>
      <div className="cortex-admin-field">
        <label htmlFor={searchId}>{t(`${keyPrefix}.search.label`)}</label>
        <input
          id={searchId}
          type="search"
          value={filters.text}
          maxLength={SEARCH_MAX}
          placeholder={t(`${keyPrefix}.search.placeholder`)}
          onChange={(event) => filters.setText(event.target.value)}
        />
      </div>
      <button type="submit" className="cortex-admin-btn">
        {t(`${keyPrefix}.search.submit`)}
      </button>
      {(filters.q || filters.status) && (
        <button type="button" className="cortex-admin-btn cortex-admin-btn--ghost" onClick={filters.clear}>
          {t(`${keyPrefix}.search.clear`)}
        </button>
      )}
      {children}
    </form>
  );
}
