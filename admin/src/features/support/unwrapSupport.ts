import { ApiError, unwrap } from '@flow-cortex/api-client';

type SuccessData<T> = Extract<T, { status: 200 | 201 | 204 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
export async function unwrapSupport<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

const PROBLEM_TYPE = /\/problems\/([a-z0-9-]+)$/;

/**
 * The machine reason of a problem response: its `code`, else the last segment of its `type`
 * (a bean-validation 400 carries only `type`), or `undefined` for any other failure.
 */
export function supportProblemCode(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || !error.problem) return undefined;
  const { code, type } = error.problem;
  if (typeof code === 'string') return code;
  return typeof type === 'string' ? PROBLEM_TYPE.exec(type)?.[1] : undefined;
}
