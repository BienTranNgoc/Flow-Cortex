import { render, screen } from '@testing-library/react';
import { Markdown } from './Markdown';

const BYPASS_ANSWERS = [
  '[Liên hệ hỗ trợ]( mailto:x@evil.example)',
  "[Gửi](mailto:x@evil.example 'hi')",
  '[Gửi](\nmailto:x@evil.example)',
  'See [Nodi](http:evil.com/x(a(b)))',
  'See [Nodi][r]\n\n[r]:\nhttp:evil.com',
];

describe('Markdown', () => {
  it('shows raw HTML as plain text and never renders it', () => {
    const { container } = render(<Markdown>{'<img src=x onerror=alert(1)>'}</Markdown>);

    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
  });

  it('renders a javascript: or data: link as plain text', () => {
    render(
      <Markdown>
        {'[x](javascript:alert(1)) and [y](data:text/html,<b>hi</b>) and [z](vbscript:msgbox)'}
      </Markdown>,
    );

    expect(screen.queryAllByRole('link')).toEqual([]);
    expect(screen.getByText('x')).toBeInTheDocument();
    expect(screen.getByText('y')).toBeInTheDocument();
    expect(screen.getByText('z')).toBeInTheDocument();
  });

  it('opens an external link in a new tab without access to the opener', () => {
    render(<Markdown>{'[Tài liệu](https://example.com/docs)'}</Markdown>);

    const link = screen.getByRole('link', { name: 'Tài liệu' });
    expect(link).toHaveAttribute('href', 'https://example.com/docs');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('renders a protocol-relative link (//host or /\\host) as plain text', () => {
    render(<Markdown>{'[a](//evil.example/x) and [b](/\\evil.example/x)'}</Markdown>);

    expect(screen.queryAllByRole('link')).toEqual([]);
    expect(screen.getByText('a')).toBeInTheDocument();
    expect(screen.getByText('b')).toBeInTheDocument();
  });

  it('shows an image as its alt text and never loads it', () => {
    const { container } = render(
      <Markdown>{'![Ảnh chụp màn hình](https://tracker.example/pixel.png)'}</Markdown>,
    );

    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('Ảnh chụp màn hình')).toBeInTheDocument();
  });

  it('keeps app-relative and mailto links in the same tab', () => {
    render(<Markdown>{'[Hồ sơ](/profile) [Email](mailto:hotro@nodi.vn)'}</Markdown>);

    const profile = screen.getByRole('link', { name: 'Hồ sơ' });
    expect(profile).toHaveAttribute('href', '/profile');
    expect(profile).not.toHaveAttribute('target');
    expect(screen.getByRole('link', { name: 'Email' })).toHaveAttribute(
      'href',
      'mailto:hotro@nodi.vn',
    );
  });

  it('opens every link in a new tab when asked to', () => {
    render(<Markdown linksInNewTab>{'[c](/settings)'}</Markdown>);

    const link = screen.getByRole('link', { name: 'c' });
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('renders headings, lists, emphasis and code', () => {
    render(<Markdown>{'## Nạp credit\n\n- Vào **Hồ sơ**\n- Chọn `Credit`'}</Markdown>);

    expect(screen.getByRole('heading', { name: 'Nạp credit' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByText('Hồ sơ').tagName).toBe('STRONG');
    expect(screen.getByText('Credit').tagName).toBe('CODE');
  });

  it('starts headings at the level the page leaves free', () => {
    const { rerender } = render(<Markdown>{'# A\n\n## B'}</Markdown>);
    expect(screen.getByRole('heading', { name: 'A', level: 4 })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'B', level: 5 })).toBeInTheDocument();

    rerender(<Markdown firstHeadingLevel={3}>{'# A\n\n## B'}</Markdown>);
    expect(screen.getByRole('heading', { name: 'A', level: 3 })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'B', level: 4 })).toBeInTheDocument();
  });

  describe('answer mode', () => {
    it('renders every link and image as its text, keeping the link text', () => {
      const { container } = render(
        <Markdown mode="answer">
          {'Xem [Hồ sơ](/profile) và [web](https://example.com) ![Ảnh](https://t.example/p.png)'}
        </Markdown>,
      );

      expect(screen.queryAllByRole('link')).toEqual([]);
      expect(container.querySelector('a, img')).toBeNull();
      expect(container).toHaveTextContent('Xem Hồ sơ và web');
    });

    it.each(BYPASS_ANSWERS)('shows no link for %j', (answer) => {
      const { container } = render(<Markdown mode="answer">{answer}</Markdown>);

      expect(screen.queryAllByRole('link')).toEqual([]);
      expect(container.querySelector('a, img')).toBeNull();
    });
  });
});
