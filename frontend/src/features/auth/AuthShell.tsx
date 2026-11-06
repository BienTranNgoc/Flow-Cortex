import { Fragment, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { useInRouterContext, useLocation, useNavigate } from 'react-router-dom';
import banner1x from '../../assets/auth/nodi-auth-banner.webp';
import banner2x from '../../assets/auth/nodi-auth-banner@2x.webp';
import { AppLanguageToggle } from '../../lib/AppLanguageToggle';
import { authPromo, type AuthPromo } from './authPromo';
import { NodiMark } from './NodiMark';
import { NodiPeek } from './NodiPeek';
import type { NodiPose } from './nodiPeekState';
import './AuthShell.css';

interface AuthShellProps {
  pose?: NodiPose;
  promo?: AuthPromo | null;
  children: ReactNode;
}

const icon = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  'aria-hidden': true,
} as const;

const chips = [
  { key: 'image', label: 'auth.shell.chips.image' },
  { key: 'video', label: 'auth.shell.chips.video' },
  { key: 'pipeline', label: 'auth.shell.chips.pipeline' },
] as const;

const chipIcons = [
  <svg key="image" {...icon}>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <circle cx="9" cy="10" r="2" />
    <path d="M21 17l-5-5-9 8" />
  </svg>,
  <svg key="video" {...icon}>
    <path d="M7 4l13 8-13 8z" />
  </svg>,
  <svg key="pipeline" {...icon}>
    <circle cx="5" cy="12" r="2.5" />
    <circle cx="19" cy="6" r="2.5" />
    <circle cx="19" cy="18" r="2.5" />
    <path d="M7.5 12h4l5-5M11.5 12l5 5" />
  </svg>,
];

const rowIcons: ReactNode[] = [
  <svg key="calendar" {...icon}>
    <rect x="3" y="5" width="18" height="16" rx="2" />
    <path d="M3 10h18M8 3v4M16 3v4" />
  </svg>,
  <svg key="clock" {...icon}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </svg>,
];

// React 18 does not know `fetchPriority`; the lowercase attribute reaches the DOM as-is.
const highPriority = { fetchpriority: 'high' } as Record<string, string>;

/** Focuses the card heading when another auth screen sent the visitor here; a first load stays put. */
function FocusHeadingOnArrival({ card }: { card: RefObject<HTMLElement> }) {
  const location = useLocation();
  const navigate = useNavigate();
  const state = location.state as { focusHeading?: unknown } | null;
  const focusHeading = state?.focusHeading === true;
  useEffect(() => {
    if (focusHeading) {
      card.current?.querySelector<HTMLElement>('h1')?.focus();
      // The router keeps state across a reload; dropping the flag stops a reload from jumping focus.
      navigate(`${location.pathname}${location.search}`, {
        replace: true,
        state: { ...state, focusHeading: false },
      });
    }
  }, [card, focusHeading, location.key, location.pathname, location.search, navigate, state]);
  return null;
}

/** Public auth frame: ocean banner stage plus the light card that wraps a page's form. */
export function AuthShell({ pose = 'idle', promo = authPromo, children }: AuthShellProps) {
  const [bannerFailed, setBannerFailed] = useState(false);
  const card = useRef<HTMLElement>(null);
  const inRouter = useInRouterContext();
  const { t } = useTranslation();
  const headline = [t('auth.shell.headline.line1'), t('auth.shell.headline.line2')];
  const tagline = [t('auth.shell.tagline.line1'), t('auth.shell.tagline.line2')];
  const brandName = t('auth.shell.brandName');

  return (
    <div className="auth-shell">
      <aside className="auth-shell__stage" aria-label={t('auth.shell.stageLabel')}>
        <div className="auth-stage">
          {!bannerFailed && (
            <img
              className="auth-stage__art"
              src={banner1x}
              srcSet={`${banner1x} 1x, ${banner2x} 2x`}
              width="1920"
              height="1080"
              decoding="async"
              alt=""
              onError={() => setBannerFailed(true)}
              {...highPriority}
            />
          )}
          <div className="auth-stage__copy">
            <span className="auth-stage__badge">
              <span>{t('auth.shell.badge.label')}</span>
              <i aria-hidden="true" />
              <span>{t('auth.shell.badge.offer')}</span>
            </span>
            <p className="auth-stage__brandline">{t('auth.shell.brandLine')}</p>
            <p className="auth-stage__headline">
              {headline.map((line, index) => (
                <Fragment key={line}>
                  {index > 0 && ' '}
                  <span>{line}</span>
                </Fragment>
              ))}
            </p>
            <p className="auth-stage__lede">{t('auth.shell.lede')}</p>
          </div>
          {chips.map(({ key, label }, index) => (
            <span key={key} className={`auth-stage__chip auth-stage__chip--${index + 1}`}>
              {chipIcons[index]}
              {t(label)}
            </span>
          ))}
          {promo && (
            <div className="auth-promo">
              <div>
                <span className="auth-promo__tag">{promo.tag}</span>
                <p className="auth-promo__big">{promo.big}</p>
                <p className="auth-promo__sub">{promo.sub}</p>
              </div>
              <span className="auth-promo__rule" aria-hidden="true" />
              <dl className="auth-promo__rows">
                {promo.rows.map((row, index) => (
                  <div key={row.label}>
                    <dt>
                      {rowIcons[index]}
                      {row.label}
                    </dt>
                    <dd>
                      {row.value}
                      {row.note && (
                        <>
                          {' '}
                          <small>{row.note}</small>
                        </>
                      )}
                    </dd>
                  </div>
                ))}
              </dl>
            </div>
          )}
        </div>
      </aside>

      <header className="auth-shell__bar">
        <span className="auth-brand__name">
          <NodiMark size={28} />
          {brandName}
        </span>
        <AppLanguageToggle />
      </header>

      <div className="auth-shell__zone">
        <main className="auth-card" ref={card}>
          {inRouter && <FocusHeadingOnArrival card={card} />}
          <NodiPeek pose={pose} />
          <div className="auth-brand">
            <span className="auth-brand__name">
              <NodiMark size={34} />
              {brandName}
            </span>
            <span className="auth-brand__sep" aria-hidden="true" />
            <span className="auth-brand__tag">
              {tagline.map((line, index) => (
                <Fragment key={line}>
                  {index > 0 && ' '}
                  <span>{line}</span>
                </Fragment>
              ))}
            </span>
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}
