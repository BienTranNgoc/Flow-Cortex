import {
  useEffect,
  useId,
  useRef,
  useState,
  type ComponentType,
  type KeyboardEvent,
  type RefObject,
} from 'react';
import { useTranslation } from 'react-i18next';
import { AssistantTab } from './AssistantTab';
import { ArticleView, GuidesTab } from './GuidesTab';
import { HomeTab } from './HomeTab';
import { RequestsTab } from './RequestsTab';
import { tabsFor, useSupportPanel, type SupportTab } from './supportPanelState';
import { useUnreadCount } from './useUnread';

/** Matches the `support.css` breakpoint below which the panel fills the screen. */
const FULL_SCREEN = '(max-width: 639.98px)';

function matchesFullScreen(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(FULL_SCREEN).matches;
}

function useFullScreen(): boolean {
  const [full, setFull] = useState(matchesFullScreen);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(FULL_SCREEN);
    const onChange = () => setFull(query.matches);
    query.addEventListener?.('change', onChange);
    return () => query.removeEventListener?.('change', onChange);
  }, []);
  return full;
}

/** Makes everything outside `panel` inert while `active`, restoring only what it changed. */
function useInertOutside(panel: RefObject<HTMLElement>, active: boolean) {
  useEffect(() => {
    if (!active) return;
    const changed: Element[] = [];
    for (let node = panel.current; node && node !== document.body; node = node.parentElement) {
      for (const sibling of Array.from(node.parentElement?.children ?? [])) {
        if (sibling !== node && !sibling.hasAttribute('inert')) {
          sibling.setAttribute('inert', '');
          changed.push(sibling);
        }
      }
    }
    return () => changed.forEach((element) => element.removeAttribute('inert'));
  }, [panel, active]);
}

const BODIES: Record<SupportTab, ComponentType> = {
  home: HomeTab,
  assistant: AssistantTab,
  guides: GuidesTab,
  requests: RequestsTab,
};

const NEXT_KEYS: Record<string, (index: number, count: number) => number> = {
  ArrowRight: (index, count) => (index + 1) % count,
  ArrowLeft: (index, count) => (index - 1 + count) % count,
  Home: () => 0,
  End: (_index, count) => count - 1,
};

interface SupportPanelProps {
  id: string;
  onClose: () => void;
}

/** The help dialog: title, close, the actor's tabs (roving tabindex) and the selected body. */
export function SupportPanel({ id, onClose }: Readonly<SupportPanelProps>) {
  const { t } = useTranslation();
  const { state, dispatch, member } = useSupportPanel();
  const tabs = tabsFor(member);
  const active = tabs.includes(state.tab) ? state.tab : 'home';
  const titleId = useId();
  const tabIdBase = useId();
  const tabRefs = useRef<Partial<Record<SupportTab, HTMLButtonElement | null>>>({});
  const hadArticle = useRef(state.article !== null);
  const panelRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const fullScreen = useFullScreen();
  const unread = useUnreadCount() > 0;
  const Body = BODIES[active];
  useInertOutside(panelRef, fullScreen);

  useEffect(() => {
    // An article opened with the panel takes focus itself.
    if (state.article === null) tabRefs.current[active]?.focus();
    // Focus moves in only when the panel opens; later tab changes move it themselves.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (hadArticle.current && state.article === null) tabRefs.current[active]?.focus();
    hadArticle.current = state.article !== null;
  }, [state.article, active]);

  // A tab switched from inside a body unmounts the control that had focus, leaving it on <body>
  // where Escape no longer reaches the panel; the tab list keeps its own focus.
  const shownTab = useRef(active);
  useEffect(() => {
    if (shownTab.current === active) return;
    shownTab.current = active;
    const focused = document.activeElement;
    if (!focused || focused === document.body) bodyRef.current?.focus();
  }, [active]);

  const onTabKey =(event: KeyboardEvent<HTMLDivElement>) => {
    const next = NEXT_KEYS[event.key];
    if (!next) return;
    event.preventDefault();
    const target = tabs[next(tabs.indexOf(active), tabs.length)] ?? 'home';
    dispatch({ type: 'selectTab', tab: target });
    tabRefs.current[target]?.focus();
  };

  return (
    <div
      ref={panelRef}
      id={id}
      className="support-panel"
      role="dialog"
      aria-labelledby={titleId}
      aria-modal={fullScreen ? true : undefined}
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose();
      }}
    >
      <div className="support-panel__header">
        <h2 id={titleId} className="support-panel__title">
          {t('support.panel.title')}
        </h2>
        <button
          type="button"
          className="support-panel__close"
          aria-label={t('support.panel.close')}
          onClick={onClose}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M6 6l12 12M18 6L6 18"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </button>
      </div>
      <div className="support-tabs" role="tablist" aria-labelledby={titleId} onKeyDown={onTabKey}>
        {tabs.map((tab) => (
          <button
            key={tab}
            ref={(element) => {
              tabRefs.current[tab] = element;
            }}
            type="button"
            role="tab"
            id={`${tabIdBase}-${tab}`}
            className="support-tabs__tab"
            aria-selected={tab === active}
            aria-controls={`${tabIdBase}-panel`}
            tabIndex={tab === active ? 0 : -1}
            aria-label={
              tab === 'requests' && unread
                ? `${t('support.tabs.requests')}. ${t('support.requests.unread')}`
                : undefined
            }
            onClick={() => dispatch({ type: 'selectTab', tab })}
          >
            {t(`support.tabs.${tab}`)}
            {tab === 'requests' && unread && (
              <span className="support-unread-dot" aria-hidden="true" />
            )}
          </button>
        ))}
      </div>
      <div
        ref={bodyRef}
        id={`${tabIdBase}-panel`}
        className="support-panel__body"
        role="tabpanel"
        tabIndex={-1}
        aria-labelledby={`${tabIdBase}-${active}`}
      >
        {state.article ? <ArticleView key={state.article} slug={state.article} /> : <Body />}
      </div>
    </div>
  );
}
