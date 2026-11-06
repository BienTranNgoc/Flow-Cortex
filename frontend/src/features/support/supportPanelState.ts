import { createContext, useContext } from 'react';

export type SupportTab = 'home' | 'assistant' | 'guides' | 'requests';

export const GUEST_TABS: readonly SupportTab[] = ['home', 'assistant'];
export const MEMBER_TABS: readonly SupportTab[] = ['home', 'assistant', 'guides', 'requests'];

/** Where a `?help=` link points: a tab, plus the request (`request-<n>`) or article (`article-<slug>`). */
export interface HelpTarget {
  tab: SupportTab;
  request?: number;
  article?: string;
}

const REQUEST_TARGET = /^request-([1-9]\d{0,9})$/;
const ARTICLE_TARGET = /^article-([a-z0-9]+(?:-[a-z0-9]+)*)$/;
const MAX_SLUG_LENGTH = 80;
const TAB_TARGETS: Record<string, SupportTab> = {
  home: 'home',
  assistant: 'assistant',
  guides: 'guides',
  requests: 'requests',
};

export function parseHelpParam(value: string | null): HelpTarget | null {
  if (value === null) return null;
  const request = REQUEST_TARGET.exec(value)?.[1];
  if (request) return { tab: 'requests', request: Number(request) };
  const article = ARTICLE_TARGET.exec(value)?.[1];
  if (article) return article.length <= MAX_SLUG_LENGTH ? { tab: 'home', article } : null;
  const tab = TAB_TARGETS[value];
  return tab ? { tab } : null;
}

export function tabsFor(member: boolean): readonly SupportTab[] {
  return member ? MEMBER_TABS : GUEST_TABS;
}

export interface SupportPanelState {
  open: boolean;
  tab: SupportTab;
  /** Slug of the article shown over the tab body, if any. */
  article: string | null;
  /** Request number the Requests tab shows. */
  request: number | null;
  /** True when the Requests tab should open its new-request form. */
  newRequest: boolean;
  /** Question the Assistant tab should send once, then clear. */
  question: string | null;
  /** What the new-request form starts with when opened from an assistant reply. */
  prefill: RequestPrefill | null;
}

export interface RequestPrefill {
  description: string;
  aiConversationId?: string;
}

export const CLOSED_PANEL: SupportPanelState = {
  open: false,
  tab: 'home',
  article: null,
  request: null,
  newRequest: false,
  question: null,
  prefill: null,
};

export type SupportPanelAction =
  | { type: 'open'; target?: HelpTarget }
  | { type: 'close' }
  | { type: 'reset' }
  | { type: 'selectTab'; tab: SupportTab }
  | { type: 'openArticle'; slug: string }
  | { type: 'closeArticle' }
  | { type: 'ask'; question: string }
  | { type: 'questionSent' }
  | { type: 'startRequest'; prefill?: RequestPrefill }
  | { type: 'selectRequest'; request: number | null };

export function supportPanelReducer(
  state: SupportPanelState,
  action: SupportPanelAction,
): SupportPanelState {
  switch (action.type) {
    case 'open':
      if (!action.target) return { ...state, open: true };
      return {
        ...state,
        open: true,
        tab: action.target.tab,
        article: action.target.article ?? null,
        request: action.target.request ?? state.request,
        newRequest: false,
      };
    case 'close':
      return { ...state, open: false };
    case 'reset':
      return CLOSED_PANEL;
    case 'selectTab':
      return { ...state, tab: action.tab, article: null, request: null };
    case 'openArticle':
      return { ...state, article: action.slug };
    case 'closeArticle':
      return { ...state, article: null };
    case 'ask':
      return { ...state, tab: 'assistant', article: null, question: action.question };
    case 'questionSent':
      return { ...state, question: null };
    case 'startRequest':
      return {
        ...state,
        tab: 'requests',
        article: null,
        request: null,
        newRequest: true,
        prefill: action.prefill ?? null,
      };
    case 'selectRequest':
      return { ...state, request: action.request, newRequest: false, prefill: null };
  }
}

export interface SupportPanelContextValue {
  state: SupportPanelState;
  dispatch: (action: SupportPanelAction) => void;
  member: boolean;
}

export const SupportPanelContext = createContext<SupportPanelContextValue | null>(null);

/** Panel state and actions for any component rendered inside the widget. */
export function useSupportPanel(): SupportPanelContextValue {
  const value = useContext(SupportPanelContext);
  if (!value) throw new Error('useSupportPanel must be used inside SupportWidget');
  return value;
}
