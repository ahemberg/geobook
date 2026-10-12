import { expect, test } from '@playwright/test';

test('upload, select, reopen and download a recording on the packaged map', async ({
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
  const name = `Synthetic waterfront ${testInfo.project.name}`;
  const original = Buffer.from(`<?xml version="1.0"?>
    <gpx version="1.1" creator="Geobook synthetic fixture" xmlns="http://www.topografix.com/GPX/1/1">
      <trk><name>${name}</name><trkseg>
        <trkpt lat="59.32" lon="18.07"><ele>12</ele><time>2025-06-18T10:00:00Z</time></trkpt>
        <trkpt lat="59.321" lon="18.071"><time>2025-06-18T10:02:00Z</time></trkpt>
        <trkpt lat="59.322" lon="18.072"><time>2025-06-18T10:02:00Z</time></trkpt>
        <trkpt lat="59.34" lon="18.09"><time>2025-06-18T11:00:00Z</time></trkpt>
      </trkseg><trkseg><trkpt lat="59.35" lon="18.10"/></trkseg></trk>
    </gpx>`);
  const existingResponse = await page.request.get('/api/tracks');
  const existing: { geometry: unknown[][] }[] = (await existingResponse.json()) as {
    geometry: unknown[][];
  }[];
  const previousPaths = existing.reduce((count, track) => count + track.geometry.length, 0);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Recorded travel' })).toBeVisible();
  await page
    .getByLabel('Choose GPX file')
    .setInputFiles({ name: 'synthetic.gpx', mimeType: 'application/gpx+xml', buffer: original });
  await expect(page.getByRole('status').filter({ hasText: 'Track imported.' })).toBeVisible();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.locator('.leaflet-overlay-pane path')).toHaveCount(previousPaths + 3);
  const download = await page.request.get(
    (await page.getByRole('link', { name: 'Download original export' }).getAttribute('href')) ?? '',
  );
  expect(await download.body()).toEqual(original);
  await page.reload();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  await page.getByRole('button', { name: new RegExp(name) }).click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toContainText(
    '5 points · 2 segments',
  );
  await expect(page.getByRole('link', { name: '© OpenStreetMap contributors' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  const unknown = await page.request.get('/api/unknown');
  expect(unknown.status()).toBe(404);
  expect((await page.request.get('/missing.js')).status()).toBe(404);
});

test('filter point-local dates then open and inspect the full recording', async ({
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
  const name = `Synthetic midnight ${testInfo.project.name}`;
  await page.goto('/');
  await page.getByLabel('Choose GPX file').setInputFiles({
    name: 'midnight.gpx',
    mimeType: 'application/gpx+xml',
    buffer: Buffer.from(`
    <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${name}</name><trkseg>
      <trkpt lat="59.32" lon="18.07"><time>2024-06-18T21:59:00Z</time></trkpt>
      <trkpt lat="59.321" lon="18.071"><time>2024-06-18T22:00:00Z</time></trkpt>
      <trkpt lat="59.322" lon="18.072"><time>2024-06-18T22:01:00Z</time></trkpt>
    </trkseg></trk></gpx>`),
  });
  await expect(page.getByRole('heading', { name })).toBeVisible();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Date filters' }).click();
  await page.getByRole('combobox', { name: 'Year', exact: true }).selectOption('2024');
  await expect(page.getByText('2024-01-01 to 2024-12-31')).toBeVisible();
  await page.getByLabel('From', { exact: true }).fill('2024-06-19');
  await page.getByLabel('To', { exact: true }).fill('2024-06-19');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  const row = page.getByRole('button', { name: new RegExp(name) });
  await expect(row).toContainText('2 matching points');
  const filteredPaths = await page.locator('.leaflet-overlay-pane path').count();
  expect(filteredPaths).toBeGreaterThan(0);
  await row.click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toContainText('3 points');
  await expect(page.getByText('2024-06-18 23:59:00+02:00')).toBeVisible();
  await page.getByRole('button', { name: 'Next point' }).click();
  await expect(page.getByText('2024-06-19 00:00:00+02:00')).toBeVisible();
  await page.getByRole('button', { name: 'Close track' }).click();
  await expect(row).toContainText('2 matching points');
  await page.getByRole('button', { name: 'All time', exact: true }).click();
  await expect(row).toContainText('3 recorded points');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});
