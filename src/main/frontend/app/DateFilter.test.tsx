import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import DateFilter from './DateFilter';

test('date controls open on request, keep invalid edits open, and return focus after applying or Escape', async () => {
  const user = userEvent.setup();
  const apply = vi.fn();
  render(<DateFilter range={null} years={[2026]} apply={apply} />);
  const toggle = screen.getByRole('button', { name: 'Date filters: All time' });
  expect(screen.queryByRole('button', { name: 'Apply dates' })).not.toBeInTheDocument();
  await user.click(toggle);
  await user.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(screen.getByRole('alert')).toHaveTextContent('Choose both dates');
  expect(apply).not.toHaveBeenCalled();
  await user.selectOptions(screen.getByLabelText('Year'), '2026');
  expect(apply).toHaveBeenCalledWith({ from: '2026-01-01', to: '2026-12-31' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
  await user.click(toggle);
  await user.click(screen.getByLabelText('From'));
  await user.keyboard('{Escape}');
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
});
