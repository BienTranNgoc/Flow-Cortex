import type { ReactNode } from 'react';
import type { StepState } from './stepDisplay';

function Glyph({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <svg
      className="pipeline-glyph"
      viewBox="0 0 16 16"
      width="16"
      height="16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

const STATUS_PATHS: Record<StepState, ReactNode> = {
  pending: <circle cx="8" cy="8" r="5.5" />,
  waiting: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M8 4.5V8l2.2 1.4" />
    </>
  ),
  running: (
    <>
      <circle cx="8" cy="8" r="5.5" strokeDasharray="3 2.5" />
      <circle cx="8" cy="8" r="1.5" fill="currentColor" />
    </>
  ),
  completed: <path d="M3.5 8.5l3 3 6-7" />,
  error: <path d="M4 4l8 8M12 4l-8 8" />,
  skipped: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M5 8h6" />
    </>
  ),
  'needs-you': (
    <path d="M6 14V7.5M6 7.5V3.5M8.5 7V2.5M11 7.5V4M6 9.5L4.5 8a1 1 0 0 0-1.5 1.3L6 14h4.5c1.4 0 2.5-1.1 2.5-2.5V7.5" />
  ),
};

/** The status icon: a shape per state, so state never rests on colour alone. */
export function StatusGlyph({ state }: Readonly<{ state: StepState }>) {
  return <Glyph>{STATUS_PATHS[state]}</Glyph>;
}

const TYPE_PATHS: Record<string, ReactNode> = {
  probe: (
    <>
      <circle cx="7" cy="7" r="4" />
      <path d="M10 10l3.5 3.5" />
    </>
  ),
  extract_audio: <path d="M2 8h1.5M5 5v6M8 3v10M11 6v4M14 7.5v1" />,
  APPROVAL_CHECKPOINT: <path d="M8 2l5 2v4c0 3-2 5-5 6-3-1-5-3-5-6V4l5-2z" />,
};

/** The step-type icon; a type without a drawing gets a plain dot. */
export function TypeGlyph({ type }: Readonly<{ type: string | undefined }>) {
  return (
    <Glyph>{TYPE_PATHS[type ?? ''] ?? <circle cx="8" cy="8" r="2" fill="currentColor" />}</Glyph>
  );
}
