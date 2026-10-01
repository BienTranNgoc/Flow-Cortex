import { setToken } from '@flow-cortex/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ADMIN_ME, stubApi, testQueryClient } from '../test/fakeApi';
import { AdminLayout } from './AdminLayout';

function Exploding(): never {
  throw new TypeError('Runs exploded');
}

const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  consoleError.mockClear();
});
afterAll(() => consoleError.mockRestore());

describe('AdminLayout', () => {
  it('contains a page that throws inside the shell, with Reload and one structured event', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-layout');
    window.history.pushState({}, '', '/runs');

    render(
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={['/runs']}>
          <Routes>
            <Route element={<AdminLayout />}>
              <Route path="runs" element={<Exploding />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(await screen.findByText(ADMIN_ME.email)).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Admin navigation' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Media jobs/ })).toBeInTheDocument();
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/runs', name: 'TypeError' }],
    ]);
  });
});
