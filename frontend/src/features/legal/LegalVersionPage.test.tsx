import { screen, waitFor } from '@testing-library/react';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { LegalVersionPage } from './LegalVersionPage';

// The tab title comes from the layout's outlet context, which these tests do not mount.
vi.mock('./legalTitle', () => ({ useLegalTitle: vi.fn() }));

const CURRENT_AT = '2026-11-10T08:00:00Z';
const OLDER_AT = '2026-06-01T08:00:00Z';

const sections = [
  {
    type: 'legalDoc',
    schemaVersion: 1,
    data: {
      title: { en: 'Terms of service', vi: 'Điều khoản dịch vụ' },
      body: { en: 'Body', vi: 'Nội dung' },
      docVersion: '2.0',
      effectiveDate: '2026-12-01',
    },
  },
];

const publication = (revisionId: string, publishedAt: string) => ({
  status: 200,
  body: { revisionId, publishedAt, slug: 'legal-terms', sections, seoMeta: {} },
});

const CURRENT_COPY: FakeReply = {
  status: 200,
  body: { slug: 'legal-terms', version: 2, publishedAt: CURRENT_AT, sections, seoMeta: {} },
};

const CURRENT = 'GET /api/v1/content/documents/legal-terms';
const NEWEST = 'GET /api/v1/content/documents/legal-terms/history/rev-new';
const OLDER = 'GET /api/v1/content/documents/legal-terms/history/rev-old';

function renderVersion(id: string) {
  return renderRoute('/legal/:doc/history/:id', `/legal/terms/history/${id}`, <LegalVersionPage />);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LegalVersionPage notice', () => {
  it('says the newest publication is the current version, not an earlier one', async () => {
    stubApi({ [CURRENT]: CURRENT_COPY, [NEWEST]: publication('rev-new', CURRENT_AT) });

    renderVersion('rev-new');

    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent('This is the current version, published on');
    expect(screen.queryByText(/earlier version, published/i)).not.toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: 'Read the current version' }),
    ).not.toBeInTheDocument();
  });

  it('keeps the earlier-version notice and its link for an older publication', async () => {
    stubApi({ [CURRENT]: CURRENT_COPY, [OLDER]: publication('rev-old', OLDER_AT) });

    renderVersion('rev-old');

    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent('This is an earlier version, published on');
    expect(screen.getByRole('link', { name: 'Read the current version' })).toHaveAttribute(
      'href',
      '/legal/terms',
    );
  });

  it('shows neither notice while the current copy is still loading', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    stubApi({
      [CURRENT]: async () => {
        await gate;
        return CURRENT_COPY;
      },
      [NEWEST]: publication('rev-new', CURRENT_AT),
    });

    const { queryClient } = renderVersion('rev-new');

    await screen.findByRole('heading', { level: 1, name: 'Terms of service' });
    expect(queryClient.isFetching()).toBeGreaterThan(0);
    expect(screen.queryByRole('note')).not.toBeInTheDocument();

    release();
    expect(await screen.findByRole('note')).toHaveTextContent('This is the current version');
  });

  it('shows no notice when the current copy cannot be read', async () => {
    stubApi({
      [CURRENT]: { status: 500, body: { status: 500 } },
      [NEWEST]: publication('rev-new', CURRENT_AT),
    });

    const { queryClient } = renderVersion('rev-new');

    await screen.findByRole('heading', { level: 1, name: 'Terms of service' });
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });
});
