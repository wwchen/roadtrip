import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { GalleryPage } from './GalleryPage';

test('renders the production component catalog', () => {
  render(<GalleryPage />);

  expect(screen.getByRole('heading', { level: 1, name: 'UI Gallery' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Inputs' })).toBeInTheDocument();
  expect(screen.getByLabelText('Trip name')).toHaveValue('Pacific coast');
  expect(screen.getByRole('checkbox', { name: 'Availability alerts' })).toBeChecked();
  expect(screen.getByText('Bowman Bay')).toBeInTheDocument();
  expect(screen.getByText('No watches yet')).toBeInTheDocument();
});

test('demonstrates the two-step confirmation behavior', async () => {
  const user = userEvent.setup();
  render(<GalleryPage />);

  await user.click(screen.getByRole('button', { name: 'Disconnect Slack' }));
  expect(screen.queryByText('Confirmation callback fired.')).toBeNull();

  await user.click(screen.getByRole('button', { name: 'Confirm disconnect slack' }));
  expect(screen.getByText('Confirmation callback fired.')).toBeInTheDocument();
});
