import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom';
import i18n from '../lib/i18n';
import { DocsPage } from './DocsPage';

function Where() {
  const { pathname, search } = useLocation();
  const type = useNavigationType();
  return (
    <output data-testid="where" data-nav={type}>
      {pathname + search}
    </output>
  );
}

function renderDocs(initialEntries: string[]) {
  return render(
    <MemoryRouter initialEntries={initialEntries} initialIndex={initialEntries.length - 1}>
      <Routes>
        <Route path="/docs" element={<DocsPage />} />
        <Route path="/docs/api" element={<DocsPage initialTab="api" />} />
        <Route path="/docs/guides" element={<DocsPage initialTab="guides" />} />
      </Routes>
      <Where />
    </MemoryRouter>,
  );
}

describe('DocsPage', () => {
  it('renders no second navigation and no main landmark of its own', () => {
    renderDocs(['/docs']);

    expect(screen.getByRole('heading', { level: 1, name: 'How FlowCortex works' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.queryByRole('main')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Overview' })).toBeInTheDocument();
  });

  it('keeps the Overview links to the guides and the API reference', () => {
    renderDocs(['/docs']);

    expect(screen.getByRole('link', { name: /Read a guided walk-through/ })).toHaveAttribute('href', '/docs/guides');
    expect(screen.getByRole('link', { name: /Browse the HTTP API/ })).toHaveAttribute('href', '/docs/api');
  });

  it('shows the section its route names', () => {
    renderDocs(['/docs/api']);

    expect(screen.getByRole('heading', { level: 2, name: 'API Reference' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });

  it('sends an old ?tab=api address to /docs/api and replaces the history entry', () => {
    renderDocs(['/before', '/docs?tab=api']);

    const where = screen.getByTestId('where');
    expect(where).toHaveTextContent(/^\/docs\/api$/);
    expect(where).toHaveAttribute('data-nav', 'REPLACE');
    expect(screen.getByRole('heading', { level: 2, name: 'API Reference' })).toBeInTheDocument();
  });

  it('sends an old ?tab=guides address to /docs/guides', () => {
    renderDocs(['/before', '/docs?tab=guides']);

    const where = screen.getByTestId('where');
    expect(where).toHaveTextContent(/^\/docs\/guides$/);
    expect(where).toHaveAttribute('data-nav', 'REPLACE');
    expect(screen.getByRole('heading', { level: 2, name: 'Guides' })).toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the header and the overview without a section nav', () => {
      renderDocs(['/docs']);

      expect(screen.getByRole('heading', { level: 1, name: 'Cách FlowCortex hoạt động' })).toBeInTheDocument();
      expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 2, name: 'Tổng quan' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Đọc hướng dẫn từng bước →' })).toHaveAttribute(
        'href',
        '/docs/guides',
      );
    });
  });
});
