import { localeOf } from '@flow-cortex/i18n';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useMatch } from 'react-router-dom';
import { labelIn } from './legalDocument';
import { docOfPath } from './legalPaths';
import { useLegalIndex } from './useLegal';

interface LegalIndexLinksProps {
  readonly className?: string;
  /** Accessible name of the list when it is not inside a labelled landmark. */
  readonly listLabel?: string;
  /** Called after a link is followed, so a menu holding the list can close. */
  readonly onNavigate?: () => void;
}

/**
 * The resolved legal index as ordered links in the active language, the one under `/legal/:doc` marked
 * current. Renders nothing while the index loads, when it is empty and when its request fails.
 */
export function LegalIndexLinks({ className, listLabel, onNavigate }: Readonly<LegalIndexLinksProps>) {
  const { i18n } = useTranslation();
  const locale = localeOf(i18n);
  const { pathname } = useLocation();
  const currentDoc = useMatch('/legal/:doc/*')?.params.doc ?? null;
  const index = useLegalIndex();
  const items = index.data?.items ?? [];
  if (items.length === 0) return null;

  return (
    <ul className={className ?? 'legal-links'} aria-label={listLabel}>
      {items.map((item) => {
        const current = currentDoc !== null && docOfPath(item.path) === currentDoc;
        return (
          <li key={item.slug}>
            <Link
              to={item.path}
              className="legal-links__link"
              aria-current={current ? (pathname === item.path ? 'page' : 'true') : undefined}
              onClick={onNavigate}
            >
              {labelIn(item.label, locale)}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
