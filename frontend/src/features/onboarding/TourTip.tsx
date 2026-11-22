import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import './onboarding.css';

export const SIDEBAR_NAV_SELECTOR = 'nav.sidebar-nav';

const GAP_PX = 16;
const MARGIN_PX = 8;

interface TourTipProps {
  onNext: () => void;
  onBack: () => void;
  onSkip: () => void;
  onClose: () => void;
}

interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

function navBox(): Box | null {
  const nav = document.querySelector(SIDEBAR_NAV_SELECTOR);
  if (!nav) return null;
  const { left, top, width, height } = nav.getBoundingClientRect();
  return { left, top, width, height };
}

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(value, max));

/**
 * A non-modal tip beside the sidebar navigation, with a ring drawn over the navigation. Both
 * are measured from the navigation and follow it when the window or the sidebar width changes.
 */
export function TourTip({ onNext, onBack, onSkip, onClose }: Readonly<TourTipProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const tipRef = useRef<HTMLDivElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const [nav, setNav] = useState<Box | null>(navBox);
  const [position, setPosition] = useState<{ left: number; top: number } | null>(null);

  useEffect(() => {
    const measure = () => setNav(navBox());
    window.addEventListener('resize', measure);
    const element = document.querySelector(SIDEBAR_NAV_SELECTOR);
    const observer =
      typeof ResizeObserver === 'function' && element ? new ResizeObserver(measure) : null;
    if (element) observer?.observe(element);
    return () => {
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, []);

  useLayoutEffect(() => {
    const tip = tipRef.current;
    if (!nav || !tip) return;
    const maxLeft = window.innerWidth - tip.offsetWidth - MARGIN_PX;
    const maxTop = window.innerHeight - tip.offsetHeight - MARGIN_PX;
    setPosition({
      left: clamp(nav.left + nav.width + GAP_PX, MARGIN_PX, maxLeft),
      top: clamp(nav.top, MARGIN_PX, maxTop),
    });
  }, [nav]);

  // A hidden element cannot take focus, so focus waits until the tip has been placed.
  const placed = position !== null || nav === null;
  useEffect(() => {
    if (placed) titleRef.current?.focus();
  }, [placed]);

  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeRef.current();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  return (
    <>
      {nav && (
        <div
          className="onboarding-ring"
          aria-hidden="true"
          style={{ left: nav.left, top: nav.top, width: nav.width, height: nav.height }}
        />
      )}
      <div
        ref={tipRef}
        role="dialog"
        aria-labelledby={titleId}
        className="onboarding-tip"
        style={
          position ?? {
            left: MARGIN_PX,
            top: MARGIN_PX,
            visibility: nav ? 'hidden' : 'visible',
          }
        }
      >
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className="onboarding-dialog__title">
          {t('onboarding.tour.title')}
        </h2>
        <p>{t('onboarding.tour.text')}</p>
        <div className="onboarding-tip__actions">
          <button type="button" onClick={onSkip}>
            {t('onboarding.tour.skip')}
          </button>
          <button type="button" onClick={onBack}>
            {t('onboarding.tour.back')}
          </button>
          <button type="button" className="onboarding-primary" onClick={onNext}>
            {t('onboarding.tour.next')}
          </button>
        </div>
      </div>
    </>
  );
}
