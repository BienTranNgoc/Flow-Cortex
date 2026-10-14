import { Link, NavLink } from 'react-router-dom';
import { logout } from '../features/auth/useAuth';
import { accountStatsEnabled } from './featureFlags';

type HeaderProps = {
  readonly email: string | undefined;
};

const navClass = ({ isActive }: { isActive: boolean }) => (isActive ? 'active' : '');

/** Rendered only inside `Layout`, which sits behind `RequireAuth` — the user is always signed in here. */
export function Header({ email }: HeaderProps) {
  return (
    <header className="cortex-header">
      <div className="cortex-header__inner">
        {/* Left: Logo + Brand */}
        <Link to="/" className="cortex-header__brand" aria-label="FlowCortex home">
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 500 500"
            width="34"
            height="34"
            aria-hidden="true"
          >
            <g fill="none" strokeLinecap="round" strokeLinejoin="round">
              <path
                d="M 255 110 C 240 106 220 108 200 120 C 180 132 170 148 152 152 C 140 156 128 170 132 195 C 136 215 148 226 172 230"
                stroke="#120074"
                strokeWidth="13"
              />
              <path d="M 152 152 C 135 158 120 180 122 210 C 124 235 138 252 165 258" stroke="#120074" strokeWidth="13" />
              <path d="M 255 110 L 255 240 L 255 330" stroke="#120074" strokeWidth="13" />
              <path d="M 172 178 L 255 178" stroke="#120074" strokeWidth="13" />
              <path
                d="M 172 230 L 245 230 L 245 285 L 140 285 C 130 285 125 275 130 260 C 135 245 150 240 165 258 L 220 285 L 225 315"
                stroke="#120074"
                strokeWidth="13"
              />
              <path d="M 225 315 L 205 315" stroke="#120074" strokeWidth="13" />
              <path
                d="M 255 110 C 275 106 315 110 335 130 C 355 150 365 175 355 195"
                stroke="#00d4ce"
                strokeWidth="13"
              />
              <path d="M 255 145 L 290 145 L 305 200 L 335 200" stroke="#00d4ce" strokeWidth="13" />
              <path
                d="M 335 170 C 360 170 375 188 368 215 C 360 240 340 250 320 250"
                stroke="#00d4ce"
                strokeWidth="13"
              />
              <path d="M 255 240 L 320 240 C 345 240 355 260 348 275 C 340 290 325 295 305 295" stroke="#00d4ce" strokeWidth="13" />
              <path d="M 255 300 C 280 295 295 315 275 335 C 265 345 272 355 278 358" stroke="#120074" strokeWidth="13" />
            </g>
            <circle cx="255" cy="110" r="16" fill="#120074" />
            <circle cx="152" cy="152" r="13" fill="#120074" />
            <circle cx="178" cy="180" r="15" fill="#120074" />
            <circle cx="255" cy="235" r="17" fill="#120074" />
            <circle cx="255" cy="335" r="14" fill="#120074" />
            <circle cx="198" cy="318" r="11" fill="#120074" />
            <circle cx="290" cy="145" r="11" fill="#00d4ce" />
            <circle cx="340" cy="155" r="16" fill="#00d4ce" />
            <circle cx="312" cy="205" r="14" fill="#00d4ce" />
            <circle cx="350" cy="275" r="17" fill="#00d4ce" />
          </svg>
          <span className="cortex-header__brand-name">FlowCortex</span>
        </Link>

        {/* Center: Navigation */}
        <nav className="cortex-header__nav" aria-label="Main navigation">
          <NavLink to="/" end className={navClass}>
            Home
          </NavLink>
          <NavLink to="/workflows" className={navClass}>
            Workflows
          </NavLink>
          <NavLink to="/profile" className={navClass}>
            Profile
          </NavLink>
          <a href="#docs">Docs</a>
          <a href="#api">API</a>
        </nav>

        {/* Right: GPU Pill + Auth */}
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
          <div className="cortex-header__user">
            {email && <span className="cortex-header__email">{email}</span>}
            <button type="button" className="cortex-btn cortex-btn--ghost" onClick={logout}>
              Sign out
            </button>
          </div>
        </div>
      </div>
    </header>
  );
}
