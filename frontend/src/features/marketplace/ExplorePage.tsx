import { MagnifyingGlass } from '@phosphor-icons/react';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import type { MarketplaceItem } from '@flow-cortex/api-client';
import { PageHeading } from '../../app/PageHeading';
import { StatusBlock } from '../../app/StatusBlock';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import {
  EXPLORE_SORTS,
  MAX_SEARCH_LENGTH,
  hasFilters,
  isCanonical,
  limitTerms,
  parse,
  serialize,
  type ExploreSort,
  type ExploreState,
  type ExploreTab,
} from './exploreSearchParams';
import { MarketplaceCard } from './MarketplaceCard';
import { PagedCards } from './PagedCards';
import { stepTypeLabelKey } from './stepTypeLabels';
import {
  ROW_SIZE,
  useMarketplaceBrowse,
  useMarketplaceFacets,
  useMarketplaceStats,
  useRecommended,
  useSavedWorkflows,
  useWorkflowCounts,
} from './useMarketplace';
import './ExplorePage.css';
import './MarketplaceGrid.css';

const SEARCH_DEBOUNCE_MS = 300;
const TABS: readonly ExploreTab[] = ['discover', 'saved'];

type Update = (patch: Partial<ExploreState>, replace?: boolean) => void;

const itemKey = (item: MarketplaceItem) => item.workflow.id ?? item.workflow.name ?? '';

function SearchField({
  value,
  onCommit,
}: Readonly<{ value: string; onCommit: (q: string) => void }>) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(value);
  const commit = useRef(onCommit);
  commit.current = onCommit;

  // A change the member did not type (clear filters, back) replaces the draft; their own typing,
  // spaces included, is left alone.
  useEffect(() => {
    setDraft((typed) => (typed.trim() === value ? typed : value));
  }, [value]);

  useEffect(() => {
    if (draft.trim() === value) return undefined;
    const timer = setTimeout(() => commit.current(draft.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [draft, value]);

  return (
    <label className="mp-toolbar__search">
      <MagnifyingGlass size={16} aria-hidden="true" />
      <span className="visually-hidden">{t('workflow.marketplace.search.label')}</span>
      <input
        type="search"
        value={draft}
        maxLength={MAX_SEARCH_LENGTH}
        placeholder={t('workflow.marketplace.search.placeholder')}
        onChange={(event: ChangeEvent<HTMLInputElement>) =>
          setDraft(limitTerms(event.target.value))
        }
      />
    </label>
  );
}

function StepTypeChips({ state, update }: Readonly<{ state: ExploreState; update: Update }>) {
  const { t } = useTranslation();
  const facets = useMarketplaceFacets();
  const chips = useMemo(() => {
    const listed = facets.data?.stepTypes ?? [];
    const missing = state.types
      .filter((type) => !listed.some((facet) => facet.type === type))
      .map((type) => ({ type, count: undefined as number | undefined }));
    return [...listed.map(({ type, count }) => ({ type, count })), ...missing];
  }, [facets.data, state.types]);

  if (chips.length === 0) return null;
  const toggle = (type: string) =>
    update({
      types: state.types.includes(type)
        ? state.types.filter((chosen) => chosen !== type)
        : [...state.types, type],
    });

  return (
    <fieldset className="mp-chips">
      <legend className="visually-hidden">{t('workflow.marketplace.filters.legend')}</legend>
      {chips.map(({ type, count }) => {
        const key = stepTypeLabelKey(type);
        const label = key ? t(key) : type;
        const pressed = state.types.includes(type);
        return (
          <button
            key={type}
            type="button"
            className={`mp-chip${pressed ? ' is-pressed' : ''}`}
            aria-pressed={pressed}
            onClick={() => toggle(type)}
          >
            {count === undefined
              ? label
              : t('workflow.marketplace.filters.chip', {
                  label,
                  count: formatNumber(count, activeLocale()),
                })}
          </button>
        );
      })}
    </fieldset>
  );
}

function Row({
  id,
  title,
  items,
  onSeeAll,
}: Readonly<{ id: string; title: string; items: MarketplaceItem[]; onSeeAll?: () => void }>) {
  const { t } = useTranslation();
  if (items.length === 0) return null;
  return (
    <section className="mp-section" aria-labelledby={id}>
      <div className="mp-section__head">
        <h2 id={id} className="mp-section__title">
          {title}
        </h2>
        {onSeeAll && (
          <button
            type="button"
            className="mp-section__link"
            aria-label={t('workflow.marketplace.rows.seeAllNamed', { row: title })}
            onClick={onSeeAll}
          >
            {t('workflow.marketplace.rows.seeAll')}
          </button>
        )}
      </div>
      <ul className="mp-grid">
        {items.map((item) => (
          <li key={itemKey(item)}>
            <MarketplaceCard item={item} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function DiscoverPanel({ state, update }: Readonly<{ state: ExploreState; update: Update }>) {
  const { t } = useTranslation();
  const filtered = hasFilters(state);
  const results = useMarketplaceBrowse({ q: state.q, types: state.types, sort: state.sort });
  const popular = useMarketplaceBrowse({
    q: '',
    types: [],
    sort: 'popular',
    size: ROW_SIZE,
    enabled: !filtered,
  });
  const recommended = useRecommended(ROW_SIZE, !filtered);
  const trendingRow = results.data?.items.slice(0, ROW_SIZE) ?? [];

  const choose = (sort: ExploreSort) => update({ sort, sortChosen: true });
  const clearFilters = () => update({ q: '', types: [], sort: 'trending', sortChosen: false });

  return (
    <>
      <div className="mp-toolbar">
        <SearchField value={state.q} onCommit={(q) => update({ q }, true)} />
        <label className="mp-toolbar__sort">
          <span>{t('workflow.marketplace.sort.label')}</span>
          <select
            value={state.sort}
            onChange={(event) => choose(event.target.value as ExploreSort)}
          >
            {EXPLORE_SORTS.map((sort) => (
              <option key={sort} value={sort}>
                {t(`workflow.marketplace.sort.${sort}`)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <StepTypeChips state={state} update={update} />

      {!filtered && (
        <>
          <Row
            id="mp-row-recommended"
            title={t('workflow.marketplace.rows.recommended')}
            items={recommended.data ?? []}
          />
          <Row
            id="mp-row-trending"
            title={t('workflow.marketplace.rows.trending')}
            items={trendingRow}
            onSeeAll={() => choose('trending')}
          />
          <Row
            id="mp-row-popular"
            title={t('workflow.marketplace.rows.popular')}
            items={popular.data?.items.slice(0, ROW_SIZE) ?? []}
            onSeeAll={() => choose('popular')}
          />
        </>
      )}

      <PagedCards
        query={results}
        heading={t('workflow.marketplace.results.heading')}
        card={(item) => <MarketplaceCard item={item} />}
        itemKey={itemKey}
        empty={
          filtered ? (
            <StatusBlock
              kind="empty"
              message={t('common.state.noMatch')}
              action={{ label: t('common.state.clearFilters'), onClick: clearFilters }}
            />
          ) : (
            <p className="mp-empty">{t('workflow.marketplace.results.empty')}</p>
          )
        }
      />
    </>
  );
}

function SavedPanel() {
  const { t } = useTranslation();
  const saved = useSavedWorkflows();
  return (
    <PagedCards
      query={saved}
      heading={t('workflow.marketplace.results.savedHeading')}
      card={(item) => <MarketplaceCard saved={item} />}
      itemKey={(item) => item.workflow.id ?? item.bookmarkedAt}
      empty={
        <StatusBlock
          kind="empty"
          message={t('workflow.marketplace.results.savedEmpty')}
          action={{ label: t('workflow.marketplace.results.savedEmptyAction'), to: '/explore' }}
        />
      }
    />
  );
}

function ExploreTabs({
  tab,
  onSelect,
  counts,
}: Readonly<{
  tab: ExploreTab;
  onSelect: (tab: ExploreTab) => void;
  counts: Record<ExploreTab, number | undefined>;
}>) {
  const { t } = useTranslation();
  const buttons = useRef<Array<HTMLButtonElement | null>>([]);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = TABS.indexOf(tab);
    let next: number;
    if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = TABS.length - 1;
    else if (event.key === 'ArrowRight') next = (current + 1) % TABS.length;
    else if (event.key === 'ArrowLeft') next = (current - 1 + TABS.length) % TABS.length;
    else return;
    event.preventDefault();
    onSelect(TABS[next] ?? 'discover');
    buttons.current[next]?.focus();
  };

  return (
    <div
      role="tablist"
      className="mp-tabs"
      aria-label={t('workflow.marketplace.tabs.label')}
      onKeyDown={onKeyDown}
    >
      {TABS.map((name, index) => {
        const count = counts[name];
        return (
          <button
            key={name}
            ref={(element) => {
              buttons.current[index] = element;
            }}
            type="button"
            role="tab"
            id={`mp-tab-${name}`}
            aria-selected={tab === name}
            aria-controls="mp-panel"
            tabIndex={tab === name ? 0 : -1}
            className={`mp-tabs__tab${tab === name ? ' is-selected' : ''}`}
            onClick={() => onSelect(name)}
          >
            {t(`workflow.marketplace.tabs.${name}`)}
            {count !== undefined && (
              <span className="mp-tabs__count">{formatNumber(count, activeLocale())}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

/** `/explore` — browse the published workflows, or switch to the ones the member saved. */
export function ExplorePage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const [params, setParams] = useSearchParams();
  const state = useMemo(() => parse(params), [params]);
  const stats = useMarketplaceStats();
  const counts = useWorkflowCounts();

  // The address is the page's state, so a value the page dropped is dropped from it too.
  useEffect(() => {
    if (!isCanonical(params)) setParams(serialize(state), { replace: true });
  }, [params, state, setParams]);

  const update = useCallback<Update>(
    (patch, replace = false) =>
      setParams((previous) => serialize({ ...parse(previous), ...patch }), { replace }),
    [setParams],
  );

  const totals = stats.data
    ? t('workflow.marketplace.totals', {
        workflows: t('workflow.marketplace.workflowCount', {
          count: stats.data.totalWorkflows,
          n: formatNumber(stats.data.totalWorkflows, locale),
        }),
        builders: t('workflow.marketplace.builderCount', {
          count: stats.data.totalBuilders,
          n: formatNumber(stats.data.totalBuilders, locale),
        }),
      })
    : null;

  return (
    <div className="mp-explore">
      <PageHeading
        title={t('workflow.marketplace.heading')}
        lede={t('workflow.marketplace.lede')}
      />
      {totals && <p className="mp-explore__totals">{totals}</p>}
      <ExploreTabs
        tab={state.tab}
        onSelect={(tab) => update({ tab })}
        counts={{ discover: stats.data?.totalWorkflows, saved: counts.data?.saved }}
      />
      <div
        role="tabpanel"
        id="mp-panel"
        aria-labelledby={`mp-tab-${state.tab}`}
        className="mp-explore__panel"
      >
        {state.tab === 'saved' ? <SavedPanel /> : <DiscoverPanel state={state} update={update} />}
      </div>
    </div>
  );
}
