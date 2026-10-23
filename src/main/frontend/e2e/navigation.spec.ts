import { expect, test } from '@playwright/test';

test('direct map navigation loads the bundled app without hiding missing resources', async ({
  page,
}) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const response = await page.goto('/map');
  expect(response?.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Recorded travel' })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Choose GPX or Takeout')).toBeAttached();
  for (const path of [
    '/api/unknown',
    '/api/tracks/999999',
    '/assets/missing.js',
    '/missing.css',
    '/unknown',
  ]) {
    expect((await page.request.get(path, { headers: { Accept: 'text/html' } })).status()).toBe(404);
  }
  expect((await page.request.get('/notices/timezones.md')).status()).toBe(200);
});
