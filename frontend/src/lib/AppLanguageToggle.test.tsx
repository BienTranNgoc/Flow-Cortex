import { fireEvent, render, screen } from '@testing-library/react';
import { AppLanguageToggle } from './AppLanguageToggle';
import i18n from './i18n';

describe('AppLanguageToggle', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('switches the open screen to the chosen language and marks it pressed', () => {
    render(<AppLanguageToggle />);
    expect(screen.getByRole('group', { name: 'Language' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'EN English' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: 'VI Tiếng Việt' }));

    expect(screen.getByRole('group', { name: 'Ngôn ngữ' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'VI Tiếng Việt' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: 'EN English' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(document.documentElement.lang).toBe('vi');
    expect(window.localStorage.getItem('flow-cortex.locale')).toBe('vi');
  });
});
