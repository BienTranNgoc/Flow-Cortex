import { FlowCortexLogo } from '@flow-cortex/ui-kit';
import './BrandPanel.css';

const BULLETS = [
  'Three production pipelines ready out of the box',
  'Human review gates built into every multi-step run',
  'Output retention and signed download links by default',
];

function NeuralBackdrop() {
  return (
    <svg
      className="brand-panel__backdrop"
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 600 600"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <radialGradient id="brand-neural-glow" cx="50%" cy="50%" r="60%">
          <stop offset="0%" stopColor="var(--aurora-cyan)" stopOpacity="0.22" />
          <stop offset="60%" stopColor="var(--aurora-indigo)" stopOpacity="0.08" />
          <stop offset="100%" stopColor="transparent" />
        </radialGradient>
        <linearGradient id="brand-neural-trace" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="var(--cta-from)" stopOpacity="0.7" />
          <stop offset="100%" stopColor="var(--violet)" stopOpacity="0.4" />
        </linearGradient>
      </defs>
      <circle cx="300" cy="300" r="280" fill="url(#brand-neural-glow)" />
      <g
        fill="none"
        stroke="url(#brand-neural-trace)"
        strokeWidth="1"
        strokeLinecap="round"
        opacity="0.35"
      >
        <path d="M 80 140 L 220 200 L 180 320 L 320 300 L 280 460 L 460 420 L 500 260 L 380 140 Z" />
        <path d="M 120 360 L 240 380 L 200 220 L 360 180 L 420 280 L 320 380" />
        <path d="M 100 220 L 260 260 L 300 140 L 420 200 L 380 340" />
      </g>
      <g fill="var(--teal-glow)" opacity="0.55">
        <circle cx="80" cy="140" r="4" />
        <circle cx="220" cy="200" r="3" />
        <circle cx="180" cy="320" r="4" />
        <circle cx="320" cy="300" r="5" />
        <circle cx="280" cy="460" r="3" />
        <circle cx="460" cy="420" r="4" />
        <circle cx="500" cy="260" r="3" />
        <circle cx="380" cy="140" r="4" />
        <circle cx="120" cy="360" r="3" />
        <circle cx="240" cy="380" r="4" />
        <circle cx="200" cy="220" r="3" />
        <circle cx="360" cy="180" r="5" />
        <circle cx="420" cy="280" r="3" />
        <circle cx="100" cy="220" r="3" />
        <circle cx="260" cy="260" r="4" />
        <circle cx="300" cy="140" r="3" />
        <circle cx="380" cy="340" r="4" />
      </g>
      <g fill="var(--violet)" opacity="0.45">
        <circle cx="160" cy="180" r="2.5" />
        <circle cx="250" cy="240" r="2.5" />
        <circle cx="340" cy="220" r="2.5" />
        <circle cx="430" cy="320" r="2.5" />
        <circle cx="220" cy="400" r="2.5" />
        <circle cx="400" cy="180" r="2.5" />
      </g>
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <polyline points="4 12 10 18 20 6" />
    </svg>
  );
}

export function BrandPanel() {
  return (
    <aside className="brand-panel" aria-hidden="false">
      <NeuralBackdrop />
      <div className="brand-panel__inner">
        <div className="brand-panel__logo">
          <FlowCortexLogo size={40} />
          <span className="brand-panel__logo-name">FlowCortex</span>
        </div>

        <p className="eyebrow">FLOWCORTEX AI 2.5</p>
        <h2 className="brand-panel__headline">
          Your AI media <span>studio</span> for production-grade pipelines.
        </h2>
        <p className="brand-panel__lede">
          Source, process, review and export media in one click — built for creators
          who need consistent output, not another toy.
        </p>

        <ul className="brand-panel__bullets">
          {BULLETS.map((label) => (
            <li key={label}>
              <span className="brand-panel__check" aria-hidden="true">
                <CheckIcon />
              </span>
              <span>{label}</span>
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}
