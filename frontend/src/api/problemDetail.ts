/**
 * RFC 9457 `application/problem+json` shape (design.md D3) — the backend's
 * uniform error contract across every endpoint.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  /** Present on a 400 Bean Validation failure — field name -> message. */
  errors?: Record<string, string>;
}

/** Thrown by {@link unwrap} for any non-2xx response. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | undefined;

  constructor(status: number, problem: ProblemDetail | undefined) {
    super(problem?.detail ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}
