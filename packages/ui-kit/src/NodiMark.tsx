interface NodiMarkProps {
  size?: number;
}

export function NodiMark({ size = 32 }: NodiMarkProps) {
  return (
    <svg className="nodi-mark" width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <path
        d="M5 19C5 10 10 5 16 5S27 10 27 19Q25 21.5 23.3 19Q21.6 21.5 19.7 19Q18 21.5 16 19Q14 21.5 12.3 19Q10.4 21.5 8.7 19Q7 21.5 5 19Z"
        fill="var(--nodi-body)"
        stroke="var(--nodi-ink)"
        strokeWidth="1.6"
      />
      <circle
        cx="12.5"
        cy="13"
        r="2.6"
        fill="var(--nodi-eye)"
        stroke="var(--nodi-ink)"
        strokeWidth="1.1"
      />
      <circle
        cx="19.5"
        cy="13"
        r="2.6"
        fill="var(--nodi-eye)"
        stroke="var(--nodi-ink)"
        strokeWidth="1.1"
      />
      <circle cx="12.2" cy="13.4" r="1.1" fill="var(--nodi-ink)" />
      <circle cx="19.2" cy="13.4" r="1.1" fill="var(--nodi-ink)" />
      <path
        d="M11 21v4M16 21.5v4.5M21 21v4"
        stroke="var(--nodi-ink)"
        strokeWidth="2.2"
        strokeLinecap="round"
      />
      <circle cx="11" cy="26.5" r="1.9" fill="var(--nodi-port)" />
      <circle cx="16" cy="27.5" r="1.9" fill="var(--nodi-port)" />
      <circle cx="21" cy="26.5" r="1.9" fill="var(--nodi-port)" />
    </svg>
  );
}
