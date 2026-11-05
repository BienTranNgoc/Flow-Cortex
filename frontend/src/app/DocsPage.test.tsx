import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../lib/i18n';
import { DocsPage } from './DocsPage';

describe('DocsPage', () => {
  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the header, the section tabs and the selected section', async () => {
      const user = userEvent.setup();
      render(
        <MemoryRouter initialEntries={['/docs']}>
          <DocsPage />
        </MemoryRouter>,
      );

      expect(screen.getByRole('heading', { level: 1, name: 'Cách FlowCortex hoạt động' })).toBeInTheDocument();
      const sections = screen.getByRole('navigation', { name: 'Các mục tài liệu' });
      expect(sections).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 2, name: 'Tổng quan' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Đọc hướng dẫn từng bước →' })).toHaveAttribute(
        'href',
        '/docs/guides',
      );

      await user.click(screen.getByRole('button', { name: 'Tài liệu tham khảo API' }));

      expect(screen.getByRole('heading', { level: 3, name: 'Xác thực' })).toBeInTheDocument();
      expect(screen.getByText(/liệt kê các quy trình bạn có thể chạy\./)).toBeInTheDocument();
    });
  });
});
