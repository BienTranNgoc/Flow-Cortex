import './flowCortexLogo.css';

interface LogoProps {
  readonly size?: number;
}

/**
 * Flow-Cortex mark — a brain half wired to a circuit half. The two halves
 * share the same node grid, so the brain side reads as neural pathways and
 * the circuit side as silicon. Teal nodes pulse on a staggered loop, the
 * rest stay steady so the eye follows the activity. Tokens drive every
 * colour so light and dark themes both look balanced.
 *
 * Shared between the member SPA and the admin console (this package is the
 * single source of truth for the mark). Consumer apps must define the four
 * `--logo-stroke-violet / --logo-stroke-teal / --logo-fill-violet /
 * --logo-fill-teal / --logo-glow-1 / --logo-glow-2` tokens.
 */
export function FlowCortexLogo({ size = 34 }: LogoProps) {
  return (
    <span className="cortex-logo" style={{ width: size, height: size }}>
      <svg
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 500 500"
        width={size}
        height={size}
        aria-hidden="true"
        className="cortex-logo__svg"
      >
        <defs>
          <filter id="cortex-logo-glow" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="6" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <linearGradient id="cortex-logo-violet" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="var(--logo-fill-violet)" />
            <stop offset="100%" stopColor="var(--logo-fill-teal)" />
          </linearGradient>
        </defs>

        <g
          fill="none"
          strokeLinecap="round"
          strokeLinejoin="round"
          filter="url(#cortex-logo-glow)"
        >
          {/* Brain half — paths and nodes that read as neural folds. */}
          <path
            d="M 255 110 C 240 106 220 108 200 120 C 180 132 170 148 152 152 C 140 156 128 170 132 195 C 136 215 148 226 172 230"
            stroke="var(--logo-stroke-violet)"
            strokeWidth="13"
          />
          <path
            d="M 152 152 C 135 158 120 180 122 210 C 124 235 138 252 165 258"
            stroke="var(--logo-stroke-violet)"
            strokeWidth="13"
          />
          <path
            d="M 255 110 L 255 240 L 255 330"
            stroke="var(--logo-stroke-violet)"
            strokeWidth="13"
          />
          <path d="M 172 178 L 255 178" stroke="var(--logo-stroke-violet)" strokeWidth="13" />
          <path
            d="M 172 230 L 245 230 L 245 285 L 140 285 C 130 285 125 275 130 260 C 135 245 150 240 165 258 L 220 285 L 225 315"
            stroke="var(--logo-stroke-violet)"
            strokeWidth="13"
          />
          <path d="M 225 315 L 205 315" stroke="var(--logo-stroke-violet)" strokeWidth="13" />
          <path
            d="M 255 300 C 280 295 295 315 275 335 C 265 345 272 355 278 358"
            stroke="var(--logo-stroke-violet)"
            strokeWidth="13"
          />

          {/* Circuit half — straight traces and a single curved loop, the silicon side. */}
          <path
            d="M 255 110 C 275 106 315 110 335 130 C 355 150 365 175 355 195"
            stroke="var(--logo-stroke-teal)"
            strokeWidth="13"
          />
          <path
            d="M 255 145 L 290 145 L 305 200 L 335 200"
            stroke="var(--logo-stroke-teal)"
            strokeWidth="13"
          />
          <path
            d="M 335 170 C 360 170 375 188 368 215 C 360 240 340 250 320 250"
            stroke="var(--logo-stroke-teal)"
            strokeWidth="13"
          />
          <path
            d="M 255 240 L 320 240 C 345 240 355 260 348 275 C 340 290 325 295 305 295"
            stroke="var(--logo-stroke-teal)"
            strokeWidth="13"
          />
        </g>

        {/* Brain nodes — violet, steady. */}
        <g fill="var(--logo-fill-violet)">
          <circle cx="255" cy="110" r="16" />
          <circle cx="152" cy="152" r="13" />
          <circle cx="178" cy="180" r="15" />
          <circle cx="255" cy="235" r="17" />
          <circle cx="255" cy="335" r="14" />
          <circle cx="198" cy="318" r="11" />
        </g>

        {/* Circuit nodes — teal, pulsing on a staggered loop. The brief asked for
            a subtle drop-shadow + pulse so the chips appear to be processing. */}
        <g fill="var(--logo-fill-teal)" className="cortex-logo__pulse">
          <circle cx="290" cy="145" r="11" style={{ animationDelay: '0s' }} />
          <circle cx="340" cy="155" r="16" style={{ animationDelay: '0.4s' }} />
          <circle cx="312" cy="205" r="14" style={{ animationDelay: '0.8s' }} />
          <circle cx="350" cy="275" r="17" style={{ animationDelay: '1.2s' }} />
        </g>
      </svg>
    </span>
  );
}
