import { expect, test, type Page } from '@playwright/test';

const PASSWORD = 'browser-journey';
const NEW_PASSWORD = 'account-journey';

async function signIn(page: Page, password: string) {
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function changePassword(page: Page, current: string, next: string) {
  await page.getByLabel('Current password').fill(current);
  await page.getByLabel('New password', { exact: true }).fill(next);
  await page.getByLabel('Repeat new password').fill(next);
  await page.getByRole('button', { name: 'Change password' }).click();
}

test('the owner changes the password and signs out everywhere', async ({
  page,
  browser,
  baseURL,
}) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const other = await browser.newContext({ baseURL: baseURL ?? '' });
  const otherSignIn = await other.request.post('/api/session', {
    data: { username: 'admin', password: PASSWORD },
  });
  expect(otherSignIn.status()).toBe(200);

  await page.goto('/map');
  await signIn(page, PASSWORD);
  await page.getByRole('link', { name: 'Account' }).click();
  await expect(page).toHaveURL(`${baseURL}/account`);
  await expect(page.getByRole('heading', { name: 'Account' })).toBeVisible();

  await changePassword(page, 'wrong password', NEW_PASSWORD);
  await expect(page.getByRole('alert')).toHaveText('The current password is wrong.');
  await changePassword(page, PASSWORD, NEW_PASSWORD);
  await expect(page.getByRole('status')).toHaveText(
    'Password changed. Other browsers are signed out.',
  );
  expect((await other.request.get('/api/tracks')).status()).toBe(401);
  await other.close();

  await page.getByRole('button', { name: 'Sign out everywhere' }).click();
  await expect(page).toHaveURL(`${baseURL}/login`);
  await page.goto('/account');
  await expect(page).toHaveURL(`${baseURL}/login?next=%2Faccount`);

  await signIn(page, NEW_PASSWORD);
  await expect(page).toHaveURL(`${baseURL}/account`);
  // The login journey shares this server, so put the original password back.
  await changePassword(page, NEW_PASSWORD, PASSWORD);
  await expect(page.getByRole('status')).toBeVisible();
});
