import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../lib/i18n';
import { RunsPage } from './RunsPage';

describe('RunsPage', () => {
  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the header, the search and status filters, and the empty state', async () => {
      const user = userEvent.setup();
      render(
        <MemoryRouter>
          <RunsPage />
        </MemoryRouter>,
      );

      expect(screen.getByRole('heading', { level: 1, name: 'Lượt chạy gần đây' })).toBeInTheDocument();
      const search = screen.getByRole('searchbox', { name: 'Tìm lượt chạy' });
      expect(search).toHaveAttribute('placeholder', 'Tìm theo tên quy trình…');
      expect(screen.getByRole('group', { name: 'Lọc theo trạng thái' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getAllByText('Mã lượt chạy').length).toBeGreaterThan(0);
      expect(screen.getAllByRole('link', { name: 'Xem chi tiết lượt chạy →' }).length).toBeGreaterThan(0);

      await user.type(search, 'no workflow has this name');

      expect(screen.getByText('Không có lượt chạy nào khớp với bộ lọc hiện tại.')).toBeInTheDocument();
    });
  });
});
