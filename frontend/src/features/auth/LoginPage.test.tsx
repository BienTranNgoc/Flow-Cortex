import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { setToken } from '@flow-cortex/api-client';
import { LoginPage } from './LoginPage';

function renderLogin(state: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/profile" element={<p>Profile landing</p>} />
          <Route path="/" element={<p>Home</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('LoginPage', () => {
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('redirects to the `from` page when a token is already present', () => {
    setToken('existing-token');

    renderLogin({ from: '/profile' });

    expect(screen.getByText('Profile landing')).toBeInTheDocument();
  });

  it('falls back to "/" when no `from` was provided', () => {
    setToken('existing-token');

    renderLogin(null);

    expect(screen.getByText('Home')).toBeInTheDocument();
  });
});
