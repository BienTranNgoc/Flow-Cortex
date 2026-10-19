export type NodiPose = 'idle' | 'look' | 'shy' | 'worried' | 'happy';

export interface NodiPeekInput {
  focused: 'email' | 'password' | null;
  pending: boolean;
  failed: boolean;
  succeeded: boolean;
}

export function nodiPeekState({ focused, pending, failed, succeeded }: NodiPeekInput): NodiPose {
  if (succeeded || pending) return 'happy';
  if (failed) return 'worried';
  if (focused === 'password') return 'shy';
  if (focused === 'email') return 'look';
  return 'idle';
}
