import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../test/fakeApi';
import { Footer } from './Footer';

const INDEX = 'GET /api/v1/content/legal-index';
const GITHUB = 'https://github.com/BienTranNgoc/Flow-Cortex';

function renderFooter() {
  return renderRoute('/', '/', <Footer />);
}

describe('Footer', () => {
  it('listsLegalEntriesInIndexOrderThenGitHub', async () => {
    stubApi({
      [INDEX]: {
        status: 200,
        body: {
          items: [
            {
              slug: 'legal-privacy',
              path: '/legal/privacy',
              label: { vi: 'Chính sách bảo mật dữ liệu', en: 'Data privacy policy' },
            },
            {
              slug: 'legal-pricing',
              path: '/legal/pricing',
              label: { vi: 'Chính sách giá', en: 'Pricing policy' },
            },
          ],
        },
      },
    });
    renderFooter();

    await userEvent.click(await screen.findByRole('button', { name: 'Legal' }));

    const menu = screen.getByRole('list', { name: 'Legal documents' });
    expect(
      within(menu)
        .getAllByRole('link')
        .map((link) => [link.textContent, link.getAttribute('href')]),
    ).toEqual([
      ['Data privacy policy', '/legal/privacy'],
      ['Pricing policy', '/legal/pricing'],
    ]);
    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/legal/privacy',
      '/legal/pricing',
      GITHUB,
    ]);
    expect(screen.getByRole('link', { name: 'FlowCortex on GitHub' })).toBeInTheDocument();
  });

  it.each([
    ['an empty list', { status: 200, body: { items: [] } }],
    ['a failing request', { status: 500, body: { status: 500 } }],
    ['an unmatched 404', undefined],
  ])('showsNoLegalButtonWhenTheIndexIsEmptyOrFails (%s)', async (_name, reply) => {
    stubApi(reply ? { [INDEX]: reply } : {});
    const { queryClient } = renderFooter();
    await settled(queryClient);

    expect(screen.queryByRole('button', { name: 'Legal' })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([GITHUB]);
  });
});
