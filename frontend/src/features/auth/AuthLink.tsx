import type { MouseEvent, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAuthNavigate } from './useAuthNavigate';

interface AuthLinkProps {
  to: string;
  children: ReactNode;
}

/** A real link to another auth screen; a plain left click swaps screens through `useAuthNavigate`. */
export function AuthLink({ to, children }: AuthLinkProps) {
  const { go } = useAuthNavigate();
  const onClick = (event: MouseEvent<HTMLAnchorElement>) => {
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return;
    }
    event.preventDefault();
    go(to);
  };
  return (
    <Link to={to} className="auth-link" onClick={onClick}>
      {children}
    </Link>
  );
}
