import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { DocsMenu } from './DocsMenu';

describe('DocsMenu', () => {
  it('opens a disclosure panel of links; Escape closes it and returns focus to the trigger', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <DocsMenu pathname="/" />
      </MemoryRouter>,
    );
    const trigger = screen.getByRole('button', { name: 'Docs' });

    await user.click(trigger);
    const panel = document.getElementById(trigger.getAttribute('aria-controls') ?? '');
    expect(panel).not.toBeNull();
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(within(panel!).getByRole('link', { name: 'API Reference' })).toHaveAttribute(
      'href',
      '/docs/api',
    );

    await user.tab();
    expect(within(panel!).getByRole('link', { name: 'Overview' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveFocus();
  });
});
