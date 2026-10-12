import { expect, test } from '@playwright/test';

test('reviewed visits persist and country exploration keeps dates and full recordings', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) =>
    route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
        'base64',
      ),
    }),
  );
  const year = testInfo.project.name === 'mobile' ? '2023' : '2022';
  const name = `Synthetic country journey ${testInfo.project.name}`;
  const buffer =
    Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${name}</name><trkseg>
    <trkpt lat="59.34" lon="18.04"><time>${year}-06-18T22:30:00Z</time></trkpt>
    <trkpt lat="52.52" lon="13.405"><time>${year}-06-19T10:00:00Z</time></trkpt>
    <trkpt lat="59.35" lon="18.04"><time>${year}-06-20T10:00:00Z</time></trkpt>
    </trkseg></trk></gpx>`);
  await page.goto('/');
  await page
    .getByLabel('Choose GPX file')
    .setInputFiles({ name: 'countries.gpx', mimeType: 'application/gpx+xml', buffer });
  await expect(page.getByRole('heading', { name })).toBeVisible();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Date filters', exact: true }).click();
  await page.getByLabel('Year', { exact: true }).selectOption(year);
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  await page.getByRole('checkbox', { name: `Select Sweden ${year}-06-19` }).check();
  await page.getByRole('checkbox', { name: `Select Sweden ${year}-06-20` }).check();
  await page.getByRole('button', { name: 'Confirm selected (2)' }).click();
  await expect(page.getByRole('button', { name: 'Explore Sweden' })).toContainText(
    '2 recorded days',
  );
  await expect(page.getByRole('button', { name: 'Explore Sweden' })).toContainText(
    `First: ${year}-06-19`,
  );
  await page.getByRole('checkbox', { name: `Select Germany ${year}-06-19` }).check();
  await page.getByRole('button', { name: 'Exclude selected (1)' }).click();
  await expect(page.getByText('No pending country days in these dates.')).toBeVisible();
  await page.getByLabel('Review state').selectOption('excluded');
  await expect(page.getByRole('group', { name: `Germany ${year}-06-19` })).toBeVisible();
  await page.getByRole('button', { name: 'Explore Sweden' }).click();
  await expect(page.getByText(`${year}-01-01 to ${year}-12-31`, { exact: true })).toBeVisible();
  await expect(page.locator('.leaflet-overlay-pane path')).toHaveCount(2);
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  await page.getByRole('button', { name: new RegExp(`${name}.*matching points`) }).click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toContainText('3 points');
  await expect(page.locator('.leaflet-overlay-pane path')).toHaveCount(3);
  await page.getByRole('button', { name: 'Close track' }).click();
  await page.getByRole('button', { name: 'All countries', exact: true }).click();
  await expect(page.locator('.leaflet-overlay-pane path')).toHaveCount(3);
  await page.reload();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Date filters', exact: true }).click();
  await page.getByLabel('Year', { exact: true }).selectOption(year);
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Explore Sweden' })).toContainText(
    '2 recorded days',
  );
  await page.getByLabel('Review state').selectOption('excluded');
  await expect(page.getByRole('group', { name: `Germany ${year}-06-19` })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({
    path: `../../../.impeccable/review/countries-${testInfo.project.name}.png`,
    fullPage: true,
  });
});
