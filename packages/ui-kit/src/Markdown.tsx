import type { ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';

const ALLOWED_PROTOCOLS = new Set(['http', 'https', 'mailto']);

// Browsers read `//host` and `/\host` as another host on the page's protocol; the Markdown
// parser hands the backslash over percent-encoded. Tabs and newlines are dropped before the check
// because browsers drop them too.
const OTHER_HOST = /^[/\\](?:[/\\]|%5c)/i;

/** Keeps http(s), mailto and same-host relative URLs; anything else becomes empty, which renders as text. */
function safeUrl(raw: string): string {
  const url = raw.replace(/[\t\n\r]/g, '');
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

interface LinkProps {
  readonly href?: string | undefined;
  readonly newTab: boolean;
  readonly children?: ReactNode;
}

function Link({ href, newTab, children }: LinkProps) {
  if (!href) return <span>{children}</span>;
  if (newTab || isExternal(href)) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    );
  }
  return <a href={href}>{children}</a>;
}

export interface MarkdownProps {
  readonly children: string;
  /** `article` keeps links that pass the URL allowlist; `answer` (model output) renders no link or image at all. */
  readonly mode?: 'article' | 'answer';
  /** Level of a top-level `#` heading: the page above leaves everything below it free. */
  readonly firstHeadingLevel?: 3 | 4;
  /** Open relative links in a new tab too, for a screen where leaving would lose unsaved text. */
  readonly linksInNewTab?: boolean;
}

const HEADING_TAGS = ['h3', 'h4', 'h5', 'h6'] as const;

function headingComponents(first: 3 | 4): Components {
  const at = (offset: number) => HEADING_TAGS[Math.min(first - 3 + offset, 3)] ?? 'h6';
  const heading =
    (offset: number): NonNullable<Components['h1']> =>
    ({ children }) => {
      const Tag = at(offset);
      return <Tag>{children}</Tag>;
    };
  return { h1: heading(0), h2: heading(1), h3: heading(2), h4: heading(3), h5: heading(4) };
}

const MEMBER_HEADINGS = headingComponents(4);
const ADMIN_HEADINGS = headingComponents(3);

/** Article and answer Markdown: raw HTML shows as text, an image shows as its alt text. */
export function Markdown({
  children,
  mode = 'article',
  firstHeadingLevel = 4,
  linksInNewTab = false,
}: MarkdownProps) {
  const components: Components = {
    ...(firstHeadingLevel === 3 ? ADMIN_HEADINGS : MEMBER_HEADINGS),
    a: ({ href, children: text }) => (
      <Link href={href} newTab={linksInNewTab}>
        {text}
      </Link>
    ),
    img: ({ alt }) => (alt ? <span>{alt}</span> : null),
  };
  const answerOnly =
    mode === 'answer' ? { disallowedElements: ['a', 'img'], unwrapDisallowed: true } : {};
  return (
    <div className="support-markdown">
      <ReactMarkdown urlTransform={safeUrl} components={components} {...answerOnly}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
