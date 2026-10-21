import { expect, test } from '@playwright/test';

test('saved choices are shared with another browser and hidden tracks stay selectable', async ({
  page,
  browser,
  baseURL,
}, testInfo) => {
  const second = await browser.newContext({
    baseURL: baseURL ?? '',
    viewport: { width: 1280, height: 800 },
    isMobile: false,
    hasTouch: false,
  });
  try {
    const other = await second.newPage();
    for (const current of [page, other]) {
      await current.route('https://tile.openstreetmap.org/**', (route) =>
        route.fulfill({
          contentType: 'image/png',
          body: Buffer.from(
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
            'base64',
          ),
        }),
      );
    }
    const name = `Synthetic controls ${testInfo.project.name}`;
    const longitude = testInfo.project.name === 'mobile' ? '18.08' : '18.07';
    await page.goto('/');
    await page.getByLabel('Choose GPX file').setInputFiles({
      name: 'controls.gpx',
      mimeType: 'application/gpx+xml',
      buffer:
        Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${name}</name><trkseg>
        <trkpt lat="59.32" lon="${longitude}"><time>2026-06-18T10:00:00Z</time></trkpt>
        <trkpt lat="59.321" lon="18.071"><time>2026-06-18T10:02:00Z</time></trkpt>
        </trkseg></trk></gpx>`),
    });
    // The first import initializes the bundled geographic indexes.
    await expect(page.getByRole('heading', { name })).toBeVisible({ timeout: 30000 });
    if (testInfo.project.name === 'mobile')
      await page.getByRole('button', { name: 'Show library' }).click();
    await other.goto('/');
    await expect(other.getByRole('button', { name: `Edit ${name}`, exact: true })).toBeVisible();
    await page.getByRole('searchbox', { name: 'Search tracks' }).fill(name);
    await page.getByRole('button', { name: `Edit ${name}`, exact: true }).click();
    const renamed = `Evening ${name}`;
    await page.getByLabel('Track name', { exact: true }).fill(renamed);
    await page.getByLabel('Track colour').fill('#123abc');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.locator('.recording-heading h2')).toHaveText(renamed);
    await expect(page.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(1);
    await page.getByRole('checkbox', { name: `Show ${renamed} on map` }).click();
    await expect(page.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(0);
    await other.reload();
    await expect(other.getByRole('checkbox', { name: `Show ${renamed} on map` })).not.toBeChecked();
    await expect(other.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(0);
    await other.getByRole('button', { name: new RegExp(`^${renamed}.*`) }).click();
    await expect(other.getByRole('region', { name: 'Complete recording' })).toContainText(
      '2 points · 1 segment',
    );
    await expect(other.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(0);
    await other.getByRole('checkbox', { name: `Show ${renamed} on map` }).click();
    await expect(other.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(1);
    await page.reload();
    if (testInfo.project.name === 'mobile')
      await page.getByRole('button', { name: 'Show library' }).click();
    await expect(page.getByRole('checkbox', { name: `Show ${renamed} on map` })).toBeChecked();
    await expect(page.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(1);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    // Leave a hidden track so the next session must honour saved visibility too.
    await page.getByRole('checkbox', { name: `Show ${renamed} on map` }).click();
    await expect(page.locator('.leaflet-overlay-pane path[stroke="#123abc"]')).toHaveCount(0);
  } finally {
    await second.close();
  }
});
