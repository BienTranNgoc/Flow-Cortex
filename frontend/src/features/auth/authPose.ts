import { createContext, useContext, useEffect } from 'react';
import type { NodiPose } from './nodiPeekState';

const SetAuthPoseContext = createContext<(pose: NodiPose) => void>(() => {});

/** Lets the page inside the auth layout steer the card mascot the layout renders. */
export const AuthPoseProvider = SetAuthPoseContext.Provider;

/** Shows `pose` on the card mascot while the calling page is mounted; `idle` once it leaves. */
export function useAuthPose(pose: NodiPose) {
  const setPose = useContext(SetAuthPoseContext);
  useEffect(() => {
    setPose(pose);
  }, [pose, setPose]);
  useEffect(() => () => setPose('idle'), [setPose]);
}
