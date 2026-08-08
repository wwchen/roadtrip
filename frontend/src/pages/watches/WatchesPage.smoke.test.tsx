import { render, screen } from '@testing-library/react';
import { WatchesPage } from './WatchesPage';

// Proves the Vitest + React Testing Library harness is wired up. Replaced by
// real behavioral tests in Phase 1.
test('renders the watches heading', () => {
  render(<WatchesPage />);
  expect(screen.getByRole('heading', { name: 'Watches' })).toBeInTheDocument();
});
