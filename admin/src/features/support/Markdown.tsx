import type { ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';

// Same URL rules as the member app's renderer, which shows what the admin previews here.
const ALLOWED_PROTOCOLS = new Set(['http', 'https', 'mailto']);
// Two leading slashes in either direction reach another host; browsers drop tabs and newlines first.
const OTHER_HOST = /^[/\\]{2}/;

/** Keeps http(s), mailto and same-host relative URLs; anything else becomes empty, rendered as text. */
function safeUrl(raw: string): string {
  const url = raw.replace(/[\t\n\r]/g, '');
  if (OTHER_HOST.test(url)) return '';
  const colon = url.indexOf(':');
  const firstDelimiter = Math.min(
    ...['/', '?', '#'].map((mark) => url.indexOf(mark)).filter((index) => index !== -1),
  );
  if (colon === -1 || colon > firstDelimiter) return url;
  return ALLOWED_PROTOCOLS.has(url.slice(0, colon).toLowerCase()) ? url : '';
}

// Every link opens in a new tab: a relative one would leave the editor and its unsaved text.
function Link({ href, children }: { href?: string | undefined; children?: ReactNode }) {
  if (!href) return <span>{children}</span>;
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  );
}

// Headings start below the page title (h1) and the preview heading (h2).
const components: Components = {
  a: ({ href, children }) => <Link href={href}>{children}</Link>,
  img: ({ alt }) => <span>{alt}</span>,
  h1: ({ children }) => <h3>{children}</h3>,
  h2: ({ children }) => <h4>{children}</h4>,
  h3: ({ children }) => <h5>{children}</h5>,
  h4: ({ children }) => <h6>{children}</h6>,
  h5: ({ children }) => <h6>{children}</h6>,
};

/** Article Markdown: raw HTML and images show as text, links pass the URL allowlist. */
export function Markdown({ children }: Readonly<{ children: string }>) {
  return (
    <div className="support-markdown">
      <ReactMarkdown urlTransform={safeUrl} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
