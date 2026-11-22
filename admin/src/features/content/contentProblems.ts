import { ApiError, unwrap } from '@flow-cortex/api-client';

type SuccessData<T> = Extract<T, { status: 200 | 201 | 204 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
export async function unwrapContent<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

const PROBLEM_TYPE = /\/problems\/([a-z0-9-]+)$/;

/** What a refused content request says, read once from the problem body. */
export interface ContentProblem {
  status: number;
  /** The machine reason (`version-conflict`, `document-incomplete`, ...), when the body names one. */
  code: string | undefined;
  /** The API's own message, already in the request's language. */
  detail: string | undefined;
  /** Field path to message, on a 400 `document-invalid`. */
  errors: Record<string, string>;
  /** Paths of the blank required texts, on a 422 `document-incomplete`. */
  fields: string[];
  /** The stored draft version, on a 409 `version-conflict`. */
  currentVersion: number | undefined;
}

/** The problem behind a failed request, or `undefined` for a failure that is not an API answer. */
export function contentProblem(error: unknown): ContentProblem | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const body: Record<string, unknown> = { ...error.problem };
  const type = typeof body.type === 'string' ? body.type : undefined;
  const { fields, currentVersion, errors } = body;
  return {
    status: error.status,
    code:
      typeof body.code === 'string' ? body.code : type ? PROBLEM_TYPE.exec(type)?.[1] : undefined,
    detail: typeof body.detail === 'string' ? body.detail : undefined,
    errors: typeof errors === 'object' && errors !== null ? (errors as Record<string, string>) : {},
    fields: Array.isArray(fields) ? fields.filter((f): f is string => typeof f === 'string') : [],
    currentVersion: typeof currentVersion === 'number' ? currentVersion : undefined,
  };
}
