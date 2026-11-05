import type { ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';

const ALLOWED_PROTOCOLS = new Set(['http', 'https', 'mailto']);

// Browsers read `//host` and `/\host` as another host on the page's protocol; the Markdown
// parser hands the backslash over percent-encoded.
const OTHER_HOST = /^[/\\](?:[/\\]|%5c)/i;

/** Keeps http(s), mailto and app-relative URLs; anything else becomes empty, which renders as text. */
function safeUrl(url: string): string {
  if (OTHER_HOST.test(url.trim())) return '';
  const colon = url.indexOf(':');
  const firstDelimiter = Math.min(
    ...['/', '?', '#'].map((mark) => url.indexOf(mark)).filter((index) => index !== -1),
  );
  if (colon === -1 || colon > firstDelimiter) return url;
  return ALLOWED_PROTOCOLS.has(url.slice(0, colon).toLowerCase()) ? url : '';
}

function isExternal(href: string): boolean {
  return /^https?:/i.test(href);
}

function Link({ href, children }: { href?: string | undefined; children?: ReactNode }) {
  if (!href) return <span>{children}</span>;
  if (isExternal(href)) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    );
  }
  return <a href={href}>{children}</a>;
}

// Headings start below the panel title (h2) and the article title (h3).
const components: Components = {
  a: ({ href, children }) => <Link href={href}>{children}</Link>,
  img: ({ alt }) => (alt ? <span>{alt}</span> : null),
  h1: ({ children }) => <h4>{children}</h4>,
  h2: ({ children }) => <h5>{children}</h5>,
  h3: ({ children }) => <h6>{children}</h6>,
  h4: ({ children }) => <h6>{children}</h6>,
  h5: ({ children }) => <h6>{children}</h6>,
};

/** Article and answer Markdown: raw HTML shows as text, links pass the URL allowlist. */
export function Markdown({ children }: Readonly<{ children: string }>) {
  return (
    <div className="support-markdown">
      <ReactMarkdown urlTransform={safeUrl} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
