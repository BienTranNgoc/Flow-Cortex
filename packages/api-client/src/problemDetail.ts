/**
 * RFC 9457 `application/problem+json` shape — the backend's uniform error
 * contract across every endpoint.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  /** Present on a 400 Bean Validation failure — field name -> message. */
  errors?: Record<string, string>;
  /** Present on a 423 account-locked problem — the ISO-8601 instant the lock ends. */
  lockedUntil?: string;
  /** The machine-readable reason, also the last segment of `type`; absent on some generic errors. */
  code?: string;
  /** When a quota or a pause lifts (ISO-8601), on `assistant-quota` and `assistant-paused`. */
  retryAt?: string;
  /** Articles still in a topic group, on `group-not-empty`. */
  articleCount?: number;
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
