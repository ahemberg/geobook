import { expect, test, type Page } from '@playwright/test';

test('a long library shows overview tiles before selecting a recording and after reload', async ({
  page,
}) => {
  await page.route('https://tile.openstreetmap.org/**', (route) =>
    route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
        'base64',
      ),
    }),
  );
  // A tall library and distant tracks put the world below the viewport if the map stretches.
  for (let index = 0; index < 14; index++) {
    const latitude = index % 2 ? 55 : -40;
    const longitude = index % 2 ? 90 : -100;
    const uploaded = await page.request.post('/api/tracks', {
      multipart: {
        file: {
          name: 'map-startup.gpx',
          mimeType: 'application/gpx+xml',
          buffer: Buffer.from(
            `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Map startup ${index}</name><trkseg><trkpt lat="${latitude}" lon="${longitude}"/><trkpt lat="${latitude + index / 100}" lon="${longitude + index / 100}"/></trkseg></trk></gpx>`,
          ),
        },
      },
    });
    expect(uploaded.status()).toBe(201);
  }
  await page.goto('/map');
  await expectTilesInViewport(page);
  await page.reload();
  await expectTilesInViewport(page);
});

async function expectTilesInViewport(page: Page) {
  await expect(page.locator('.leaflet-overlay-pane path').first()).toBeAttached();
  await expect
    .poll(() =>
      page.locator('.map').evaluate((map) => {
        const bounds = map.getBoundingClientRect();
        return [...map.querySelectorAll('.leaflet-tile-loaded')].some((tile) => {
          const box = tile.getBoundingClientRect();
          return (
            box.bottom > Math.max(0, bounds.top) &&
            box.top < Math.min(innerHeight, bounds.bottom) &&
            box.right > Math.max(0, bounds.left) &&
            box.left < Math.min(innerWidth, bounds.right)
          );
        });
      }),
    )
    .toBe(true);
}
