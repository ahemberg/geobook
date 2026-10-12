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
  const longitude = testInfo.project.name === 'mobile' ? '19.07' : '18.07';
  const original = Buffer.from(`<?xml version="1.0"?>
    <gpx version="1.1" creator="Geobook synthetic fixture" xmlns="http://www.topografix.com/GPX/1/1">
      <trk><name>${name}</name><trkseg>
        <trkpt lat="59.32" lon="${longitude}"><ele>12</ele><time>2025-06-18T10:00:00Z</time></trkpt>
        <trkpt lat="59.321" lon="18.071"><time>2025-06-18T10:02:00Z</time></trkpt>
        <trkpt lat="59.322" lon="18.072"><time>2025-06-18T10:02:00Z</time></trkpt>
        <trkpt lat="59.34" lon="18.09"><time>2025-06-18T11:00:00Z</time></trkpt>
      </trkseg><trkseg><trkpt lat="59.35" lon="18.10"/></trkseg></trk>
    </gpx>`);
  const existingResponse = await page.request.get('/api/tracks');
  const existing: { geometry: unknown[][]; visible: boolean }[] =
    (await existingResponse.json()) as {
      geometry: unknown[][];
      visible: boolean;
    }[];
  const previousPaths = existing
    .filter((track) => track.visible)
    .reduce((count, track) => count + track.geometry.length, 0);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Recorded travel' })).toBeVisible();
  await page
    .getByLabel('Choose GPX file')
    .setInputFiles({ name: 'synthetic.gpx', mimeType: 'application/gpx+xml', buffer: original });
  await expect(page.getByRole('status').filter({ hasText: '1 imported' })).toBeVisible();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.locator('.leaflet-overlay-pane path')).toHaveCount(previousPaths + 3);
  await page.getByText('Review import results').click();
  const download = await page.request.get(
    (await page.getByRole('link', { name: /^Download original export/ }).getAttribute('href')) ??
      '',
  );
  expect(await download.body()).toEqual(original);
  await page.reload();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  await page.getByRole('button', { name: new RegExp(`${name}.*recorded points`) }).click();
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

test('batch collection import reports partial failure and skips repeated recordings', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const latitude = testInfo.project.name === 'mobile' ? '41' : '42';
  const name = `Collection walk ${testInfo.project.name}`;
  const track = `<trk><name>${name}</name><trkseg><trkpt lat="${latitude}" lon="7"/></trkseg></trk>`;
  const collection = Buffer.from(
    `<gpx xmlns="http://www.topografix.com/GPX/1/1">${track}<trk><name>Bad point</name><trkseg><trkpt lat="91" lon="7"/></trkseg></trk><trk><name>Second walk</name><trkseg><trkpt lat="${latitude}" lon="8"/></trkseg></trk></gpx>`,
  );
  const individual = Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1">${track}</gpx>`);
  await page.goto('/');
  await page.getByLabel('Choose GPX file').setInputFiles([
    { name: 'collection.gpx', mimeType: 'application/gpx+xml', buffer: collection },
    { name: 'individual.gpx', mimeType: 'application/gpx+xml', buffer: individual },
  ]);
  await expect(
    page.getByRole('status').filter({ hasText: '2 imported · 1 duplicate · 1 failed' }),
  ).toBeVisible();
  await expect(
    page.getByText('Warning: Some points have no timestamps. Their dates remain unknown.').first(),
  ).toBeVisible();
  const links = page.getByRole('link', { name: /^Download original export/ });
  expect(
    await (await page.request.get((await links.first().getAttribute('href')) ?? '')).body(),
  ).toEqual(collection);
  expect(
    await (await page.request.get((await links.last().getAttribute('href')) ?? '')).body(),
  ).toEqual(individual);
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  await page.getByRole('button', { name: new RegExp(`${name}.*recorded points`) }).click();
  await expect(
    page
      .getByRole('region', { name: 'Complete recording' })
      .getByRole('link', { name: 'Download GPX' }),
  ).toHaveCount(2);
  const before = (await (await page.request.get('/api/tracks')).json()) as unknown[];
  await page
    .getByLabel('Choose GPX file')
    .setInputFiles({ name: 'collection.gpx', mimeType: 'application/gpx+xml', buffer: collection });
  await expect(
    page.getByRole('status').filter({ hasText: '0 imported · 2 duplicate · 1 failed' }),
  ).toBeVisible();
  expect(await (await page.request.get('/api/tracks')).json()).toHaveLength(before.length);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
