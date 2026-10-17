import { Link, NavLink, useLocation } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { FlowCortexLogo } from './FlowCortexLogo';
import { ThemeToggle } from './ThemeToggle';
import { accountStatsEnabled } from './featureFlags';
import { DocsMenu } from './DocsMenu';
import { PRIMARY_NAV_ITEMS } from './navItems';
import { UserMenu } from './UserMenu';

type HeaderProps = {
  readonly email: string | undefined;
};

const navClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'cortex-header__nav-link is-active' : 'cortex-header__nav-link';

/** Rendered only inside `Layout`, which sits behind `RequireAuth` — the user is always signed in here. */
export function Header({ email }: HeaderProps) {
  const meQuery = useMeQuery();
  const displayName = meQuery.data?.name;
  const location = useLocation();
  return (
    <header className="cortex-header">
      <div className="cortex-header__inner">
        <Link to="/" className="cortex-header__brand" aria-label="FlowCortex home">
          <FlowCortexLogo size={34} />
          <span className="cortex-header__brand-name">FlowCortex</span>
        </Link>

        <nav className="cortex-header__nav" aria-label="Main navigation">
          {PRIMARY_NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end ?? false}
              className={navClass}
            >
              {item.label}
            </NavLink>
          ))}
          <DocsMenu pathname={location.pathname} />
        </nav>

        <div className="cortex-header__actions">
          {accountStatsEnabled() && (
            <span className="cortex-gpu-pill">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                viewBox="0 0 24 24"
                width="12"
                height="12"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" />
              </svg>
              <span>18 GPU Credits</span>
            </span>
          )}
          <ThemeToggle compact />
          <UserMenu name={displayName} email={email} />
        </div>
      </div>
    </header>
  );
}
