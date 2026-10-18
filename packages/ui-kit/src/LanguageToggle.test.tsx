import { fireEvent, render, screen } from '@testing-library/react';
import { LanguageToggle } from './LanguageToggle';

const LABELS = { group: 'Language', vi: 'Tiếng Việt', en: 'English' };

describe('LanguageToggle', () => {
  it('marks the active language as pressed and offers the other one', () => {
    const onChange = vi.fn();
    render(<LanguageToggle value="vi" onChange={onChange} labels={LABELS} />);

    const group = screen.getByRole('group', { name: 'Language' });
    const viButton = screen.getByRole('button', { name: 'VI Tiếng Việt' });
    const enButton = screen.getByRole('button', { name: 'EN English' });
    expect(group).toContainElement(viButton);
    expect(viButton).toHaveTextContent('VI');
    expect(enButton).toHaveTextContent('EN');
    expect(viButton).toHaveAttribute('aria-pressed', 'true');
    expect(enButton).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(enButton);

    expect(onChange).toHaveBeenCalledWith('en');
  });
});
