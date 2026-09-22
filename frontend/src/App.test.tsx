import { render, screen } from '@testing-library/react';
import { App } from './App';

// task 4.7 replaced the group-1 skeleton screen with the real auth-guarded
// app: an unauthenticated visit to any route now redirects to /login
// (design.md D3, J-1) rather than rendering a static "Flow-Cortex" heading.
describe('App', () => {
  it('redirects an unauthenticated visitor to the sign-in page', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });
});
