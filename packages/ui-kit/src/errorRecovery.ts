export type Recovery = 'retry' | 'reload' | 'silent';

export interface RecoveryInput {
  variant: 'root' | 'page';
  failedRetries: number;
  canRetry: boolean;
  silent: boolean;
}

/** Decides what a failed boundary offers; the first matching rule wins. */
export function recoveryFor({ variant, failedRetries, canRetry, silent }: RecoveryInput): Recovery {
  if (silent) {
    return 'silent';
  }
  if (variant === 'page' && canRetry && failedRetries === 0) {
    return 'retry';
  }
  return 'reload';
}
