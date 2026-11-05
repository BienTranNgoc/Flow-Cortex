import { useAuthToken } from '@flow-cortex/api-client';
import { useCallback, useEffect, useId, useMemo, useReducer, useRef } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { SupportLauncher } from './SupportLauncher';
import { SupportPanel } from './SupportPanel';
import {
  CLOSED_PANEL,
  parseHelpParam,
  SupportPanelContext,
  supportPanelReducer,
  tabsFor,
} from './supportPanelState';
import { useUnreadCount } from './useUnread';
import './support.css';

const CLEARANCE_CLASS = 'support-widget-on';
const PENDING_HELP_KEY = 'nodi.support.pendingHelp';
const PENDING_HELP_TTL_MS = 30 * 60_000;

// A member-only `?help=` target opened signed out waits here: session storage outlives the
// sign-in page, whichever way the visitor signs in, without depending on router state.
function savePendingHelp(help: string) {
  try {
    window.sessionStorage.setItem(PENDING_HELP_KEY, JSON.stringify({ help, at: Date.now() }));
  } catch {
    // Blocked storage: the link opens nothing after sign-in, as without this hand-off.
  }
}

function takePendingHelp(): string | null {
  try {
    const raw = window.sessionStorage.getItem(PENDING_HELP_KEY);
    window.sessionStorage.removeItem(PENDING_HELP_KEY);
    const saved = raw ? (JSON.parse(raw) as { help?: unknown; at?: unknown }) : null;
    const fresh = typeof saved?.at === 'number' && Date.now() - saved.at < PENDING_HELP_TTL_MS;
    return fresh && typeof saved?.help === 'string' ? saved.help : null;
  } catch {
    return null;
  }
}

/** Help button + panel, mounted once beside the routes; also opens the panel from a `?help=` link. */
export function SupportWidget() {
  const member = useAuthToken() !== null;
  const unread = useUnreadCount() > 0;
  const [state, dispatch] = useReducer(supportPanelReducer, CLOSED_PANEL);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const wasOpen = useRef(false);
  const panelId = useId();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const help = params.get('help');

  useEffect(() => {
    document.body.classList.add(CLEARANCE_CLASS);
    return () => document.body.classList.remove(CLEARANCE_CLASS);
  }, []);

  useEffect(() => {
    if (help === null) return;
    const target = parseHelpParam(help);
    if (target && !tabsFor(member).includes(target.tab)) savePendingHelp(help);
    else if (target) dispatch({ type: 'open', target });
    const next = new URLSearchParams(params);
    next.delete('help');
    setParams(next, { replace: true, state: location.state });
  }, [help, member, params, setParams, location.state]);

  useEffect(() => {
    if (!member) return;
    const target = parseHelpParam(takePendingHelp());
    if (target) dispatch({ type: 'open', target });
  }, [member]);

  // Runs after the panel's own cleanup, so the launcher is no longer inert when it takes focus.
  useEffect(() => {
    if (wasOpen.current && !state.open) launcherRef.current?.focus();
    wasOpen.current = state.open;
  }, [state.open]);

  const close = useCallback(() => dispatch({ type: 'close' }), []);

  const context = useMemo(() => ({ state, dispatch, member }), [state, member]);

  return (
    <SupportPanelContext.Provider value={context}>
      <div className="support-widget">
        <SupportLauncher
          ref={launcherRef}
          expanded={state.open}
          unread={unread}
          controls={state.open ? panelId : undefined}
          onClick={() => (state.open ? close() : dispatch({ type: 'open' }))}
        />
        {state.open && <SupportPanel id={panelId} onClose={close} />}
      </div>
    </SupportPanelContext.Provider>
  );
}
