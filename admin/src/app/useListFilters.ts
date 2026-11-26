import { useState, type FormEvent } from 'react';

export interface ListFilters<S extends string> {
  readonly page: number;
  readonly status: S | '';
  /** The search the list reads: the box's text as it was submitted, trimmed. */
  readonly q: string;
  /** What the search box shows, submitted or not. */
  readonly text: string;
  readonly setPage: (page: number) => void;
  readonly setText: (text: string) => void;
  readonly changeStatus: (status: string) => void;
  readonly search: (event: FormEvent) => void;
  readonly clear: () => void;
}

/** Page, status filter and name search of a paged admin list; every filter change returns to the first page. */
export function useListFilters<S extends string>(): ListFilters<S> {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<S | ''>('');
  const [q, setQ] = useState('');
  const [text, setText] = useState('');

  return {
    page,
    status,
    q,
    text,
    setPage,
    setText,
    changeStatus: (next) => {
      setStatus(next as S | '');
      setPage(0);
    },
    search: (event) => {
      event.preventDefault();
      setQ(text.trim());
      setPage(0);
    },
    clear: () => {
      setStatus('');
      setQ('');
      setText('');
      setPage(0);
    },
  };
}
