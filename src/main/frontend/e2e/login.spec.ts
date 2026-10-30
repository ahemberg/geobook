import { expect, test, type Page } from '@playwright/test';

async function signIn(page: Page, password: string) {
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('the owner signs in, returns to the requested page and signs out', async ({
  page,
  baseURL,
}) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));

  await page.goto('/');
  await expect(page).toHaveURL(`${baseURL}/login?next=%2F`);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await signIn(page, 'wrong password');
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');

  await signIn(page, 'browser-journey');
  await expect(page).toHaveURL(`${baseURL}/`);
  await expect(page.getByRole('heading', { name: 'Recorded travel' })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(`${baseURL}/login`);
  await page.goto('/map');
  await expect(page).toHaveURL(`${baseURL}/login?next=%2Fmap`);

  await signIn(page, 'browser-journey');
  await expect(page).toHaveURL(`${baseURL}/map`);
  await expect(page.getByRole('heading', { name: 'Recorded travel' })).toBeVisible();
  // A session that ends elsewhere sends the next API call back to the login page.
  await page.request.delete('/api/session');
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  await expect(page).toHaveURL(`${baseURL}/login?next=%2Fmap`);
});
