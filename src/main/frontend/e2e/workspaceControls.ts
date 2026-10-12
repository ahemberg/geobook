import type { Page } from '@playwright/test';

export async function openDateFilters(page: Page) {
  const toggle = page.getByRole('button', { name: /^Date filters:/ });
  if ((await toggle.getAttribute('aria-expanded')) === 'false') await toggle.click();
}

export async function openLibrary(page: Page) {
  const toggle = page.getByRole('button', { name: 'Show library' });
  if (await toggle.isVisible()) await toggle.click();
}
