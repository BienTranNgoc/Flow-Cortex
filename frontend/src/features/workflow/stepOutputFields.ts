type Report = Record<string, unknown>;

interface FieldDefinition {
  label: string;
  keys: readonly string[];
  format: (report: Report) => string | null;
}

export interface OutputField {
  label: string;
  value: string;
}

const scalar = (value: unknown) =>
  typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : null;

const text = (key: string) => (report: Report) => scalar(report[key]);

const seconds = (ms: unknown) => (typeof ms === 'number' ? `${(ms / 1000).toFixed(1)} s` : null);

const megabytes = (bytes: unknown) =>
  typeof bytes === 'number' ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : null;

/** The one label table for structured step output: what the page shows and the summary file lists. */
const FIELDS: readonly FieldDefinition[] = [
  { label: 'Duration', keys: ['durationMs'], format: (report) => seconds(report.durationMs) },
  {
    label: 'Dimensions',
    keys: ['width', 'height'],
    format: ({ width, height }) =>
      typeof width === 'number' && typeof height === 'number' ? `${width} × ${height}` : null,
  },
  { label: 'Video codec', keys: ['videoCodec'], format: text('videoCodec') },
  { label: 'Audio codec', keys: ['audioCodec'], format: text('audioCodec') },
];

/** Keys no member can read (a demuxer list); kept for "Show raw" and the technical details file. */
const TECHNICAL_KEYS: ReadonlySet<string> = new Set(['container']);

const KNOWN_KEYS: ReadonlySet<string> = new Set(FIELDS.flatMap((field) => field.keys));

function humanise(key: string): string {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** A key outside the table gets a humanised label; a nested value is left to "Show raw". */
function otherField(key: string, value: unknown): OutputField | null {
  if (key.endsWith('Bytes')) {
    const size = megabytes(value);
    return size === null ? null : { label: humanise(key.slice(0, -'Bytes'.length)), value: size };
  }
  const shown = scalar(value);
  return shown === null ? null : { label: humanise(key), value: shown };
}

/** The output parsed as a JSON object, or `null` when it is anything else (plain text, a list…). */
export function parseReport(output: string): Report | null {
  try {
    const parsed: unknown = JSON.parse(output);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Report)
      : null;
  } catch {
    return null;
  }
}

/** The labelled fields of a report, in table order: absent (`null`) and technical fields left out. */
export function outputFields(report: Report): OutputField[] {
  const known = FIELDS.flatMap((field) => {
    const value = field.format(report);
    return value === null ? [] : [{ label: field.label, value }];
  });
  const others = Object.entries(report).flatMap(([key, value]) => {
    if (KNOWN_KEYS.has(key) || TECHNICAL_KEYS.has(key)) {
      return [];
    }
    const field = otherField(key, value);
    return field ? [field] : [];
  });
  return [...known, ...others];
}
