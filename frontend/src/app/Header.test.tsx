import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Header } from './Header';

describe('Header', () => {
  it('offers the language toggle to a signed-in member', () => {
    render(
      <MemoryRouter>
        <Header email="member1@flowcortex.dev" />
      </MemoryRouter>,
    );

    expect(screen.getByRole('group', { name: 'Language' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'VI Tiếng Việt' })).toBeInTheDocument();
  });
});
