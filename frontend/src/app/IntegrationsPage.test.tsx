import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../lib/i18n';
import { IntegrationsPage } from './IntegrationsPage';

describe('IntegrationsPage', () => {
  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the header, the provider groups and the social accounts tab', async () => {
      const user = userEvent.setup();
      render(<IntegrationsPage />);

      expect(screen.getByRole('heading', { level: 1, name: 'Tích hợp' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Nhà cung cấp AI' })).toHaveAttribute('aria-pressed', 'true');
      const text = screen.getByRole('region', { name: 'LLM văn bản' });
      expect(within(text).getAllByText('Đã kết nối')).toHaveLength(2);
      expect(within(text).getAllByRole('button', { name: 'Thêm khóa' })).toHaveLength(2);
      expect(within(text).getByText('dùng lần cuối hôm qua')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Tài khoản mạng xã hội' }));

      expect(screen.getByText('— chưa kết nối —')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Kết nối' })).toBeInTheDocument();
    });
  });
});
