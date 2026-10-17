import { useId } from 'react';
import './SearchBar.css';

interface SearchBarProps {
  readonly value: string;
  readonly onChange: (next: string) => void;
  readonly placeholder?: string;
}

export function SearchBar({ value, onChange, placeholder = 'Search workflows…' }: Readonly<SearchBarProps>) {
  const id = useId();
  return (
    <div className="dashboard-search" role="search">
      <label htmlFor={id} className="visually-hidden">
        Search workflows
      </label>
      <span className="dashboard-search__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" role="presentation">
          <circle cx="11" cy="11" r="6" />
          <path d="M20 20l-4-4" />
        </svg>
      </span>
      <input
        id={id}
        type="search"
        className="dashboard-search__input"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete="off"
        spellCheck={false}
      />
      {value && (
        <button
          type="button"
          className="dashboard-search__clear"
          aria-label="Clear search"
          onClick={() => onChange('')}
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" role="presentation">
            <path d="M6 6l12 12M18 6l-12 12" />
          </svg>
        </button>
      )}
    </div>
  );
}
