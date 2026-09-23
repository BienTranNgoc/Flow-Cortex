import { render, screen } from '@testing-library/react';
import { App } from './App';

// An unauthenticated visit to any route redirects to /login rather than
// rendering a static "Flow-Cortex" heading.
describe('App', () => {
  it('redirects an unauthenticated visitor to the sign-in page', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });
});
