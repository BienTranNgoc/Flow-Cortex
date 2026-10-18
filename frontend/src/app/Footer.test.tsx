import { render, screen } from '@testing-library/react';
import { Footer } from './Footer';

describe('Footer', () => {
  it('links only to destinations that exist', () => {
    render(<Footer />);

    const links = screen.getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'https://github.com/BienTranNgoc/Flow-Cortex',
    ]);
    expect(screen.getByRole('link', { name: 'FlowCortex on GitHub' })).toBeInTheDocument();
  });
});
