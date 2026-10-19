import type { NodiPose } from './nodiPeekState';

interface NodiPeekProps {
  pose: NodiPose;
}

const outline = { stroke: 'var(--nodi-ink)', strokeLinecap: 'round', fill: 'none' } as const;
const limb = { stroke: 'var(--nodi-body)', strokeLinecap: 'round', fill: 'none' } as const;

/** Card-edge mascot; part groups are shown or hidden by CSS keyed on `data-pose`. */
export function NodiPeek({ pose }: NodiPeekProps) {
  return (
    <svg className="nodi-peek" data-pose={pose} viewBox="0 0 120 120" aria-hidden="true">
      <path
        d="M20 84C20 44 38 22 60 22S100 44 100 84Q95 92 90 86Q85 92 80 86Q75 92 70 86Q65 92 60 86Q55 92 50 86Q45 92 40 86Q35 92 30 86Q25 92 20 84Z"
        fill="var(--nodi-body)"
        stroke="var(--nodi-ink)"
        strokeWidth="5"
        strokeLinejoin="round"
      />
      <path
        d="M30 54Q33 40 44 34"
        stroke="var(--nodi-eye)"
        strokeWidth="4"
        strokeLinecap="round"
        fill="none"
        opacity=".8"
      />
      <circle cx="33" cy="70" r="4.5" fill="var(--nodi-cheek)" />
      <g className="nodi-peek__eyes">
        <circle
          cx="48"
          cy="58"
          r="12"
          fill="var(--nodi-eye)"
          stroke="var(--nodi-ink)"
          strokeWidth="4"
        />
        <circle
          cx="74"
          cy="58"
          r="12"
          fill="var(--nodi-eye)"
          stroke="var(--nodi-ink)"
          strokeWidth="4"
        />
        <g className="nodi-peek__pupils">
          <circle cx="46" cy="61" r="5.5" fill="var(--nodi-ink)" />
          <circle cx="72" cy="61" r="5.5" fill="var(--nodi-ink)" />
          <circle cx="44" cy="59" r="1.8" fill="var(--nodi-eye)" />
          <circle cx="70" cy="59" r="1.8" fill="var(--nodi-eye)" />
        </g>
      </g>
      <path className="nodi-peek__mouth" d="M54 74Q61 80 68 74" strokeWidth="4" {...outline} />
      <g className="nodi-peek__worried">
        <path d="M37 41Q46 39 55 33" strokeWidth="3.5" {...outline} />
        <path d="M85 41Q76 39 67 33" strokeWidth="3.5" {...outline} />
        <ellipse cx="61" cy="77" rx="3.5" ry="4.5" fill="var(--nodi-ink)" />
      </g>
      <g className="nodi-peek__happy">
        <path d="M40 61Q48 50 56 61" strokeWidth="4" strokeLinejoin="round" {...outline} />
        <path d="M66 61Q74 50 82 61" strokeWidth="4" strokeLinejoin="round" {...outline} />
        <path
          d="M51 71Q61 85 71 71Z"
          fill="var(--nodi-ink)"
          stroke="var(--nodi-ink)"
          strokeWidth="3"
          strokeLinejoin="round"
        />
        <path d="M56 77Q61 81 66 77" fill="var(--nodi-blush)" />
      </g>
      <g className="nodi-peek__shy">
        <path d="M30 98C30 80 40 66 54 60" strokeWidth="12" {...outline} />
        <path d="M30 98C30 80 40 66 54 60" strokeWidth="6" {...limb} />
        <path d="M92 98C92 80 82 66 68 60" strokeWidth="12" {...outline} />
        <path d="M92 98C92 80 82 66 68 60" strokeWidth="6" {...limb} />
        <circle cx="36" cy="72" r="5" fill="var(--nodi-blush)" opacity=".6" />
        <circle cx="86" cy="72" r="5" fill="var(--nodi-blush)" opacity=".6" />
      </g>
      <path d="M44 88C44 98 40 104 40 112" strokeWidth="10" {...outline} />
      <path d="M44 88C44 98 40 104 40 112" strokeWidth="5" {...limb} />
      <path d="M76 88C76 98 80 104 80 112" strokeWidth="10" {...outline} />
      <path d="M76 88C76 98 80 104 80 112" strokeWidth="5" {...limb} />
    </svg>
  );
}
