import { expect, test, type Page } from '@playwright/test';
import { openDateFilters } from './workspaceControls';

async function painted(page: Page) {
  return page.locator('.leaflet-heat-pane canvas').evaluateAll((canvases) =>
    (canvases as HTMLCanvasElement[]).some((canvas) =>
      canvas
        .getContext('2d')
        ?.getImageData(0, 0, canvas.width, canvas.height)
        .data.some((value, index) => index % 4 === 3 && value > 0),
    ),
  );
}

test('the heatmap shows time spent on a greyed map and rescales without refetching', async ({
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
  const longitude = testInfo.project.name === 'mobile' ? '24.94' : '24.93';
  const upload = await page.request.post('/api/tracks', {
    multipart: {
      file: {
        name: 'heat.gpx',
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk>
          <name>Synthetic heat walk ${testInfo.project.name}</name><trkseg>
          <trkpt lat="60.17" lon="${longitude}"><time>2021-05-02T08:00:00Z</time></trkpt>
          <trkpt lat="60.171" lon="${longitude}"><time>2021-05-02T09:00:00Z</time></trkpt>
          <trkpt lat="60.171" lon="${longitude}1"><time>2021-05-02T11:00:00Z</time></trkpt>
          </trkseg></trk></gpx>`),
      },
    },
  });
  expect(upload.status()).toBe(201);
  await expect
    .poll(async () => {
      const summary = (await (await page.request.get('/api/heatmap/summary')).json()) as {
        building: boolean;
      };
      return summary.building;
    })
    .toBe(false);

  const tiles: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/heatmap/tiles/')) tiles.push(request.url());
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Heatmap', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Heatmap', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await page.getByRole('link', { name: 'Skip to heatmap' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main', { name: 'Heatmap' })).toBeFocused();

  await expect(page.locator('.leaflet-control-attribution')).toBeVisible();
  await expect(page.locator('.leaflet-control-attribution')).toContainText('OpenStreetMap');
  await expect(page.locator('.heatmap-basemap')).toHaveCSS(
    'filter',
    'grayscale(1) brightness(0.55)',
  );
  await expect.poll(() => painted(page)).toBe(true);

  const before = tiles.length;
  const loaded = new Set(tiles);
  expect(loaded.size).toBeGreaterThan(0);
  await page.locator('.heatmap-panel .leaflet-container').focus();
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowUp');
  await page.waitForTimeout(400);
  expect(tiles.slice(before).filter((url) => loaded.has(url))).toEqual([]);
  expect(await painted(page)).toBe(true);
  await page.screenshot({
    path: `../../../.impeccable/review/heatmap-${testInfo.project.name}.png`,
  });
});

test('the heatmap shares the period with Map and says when a period has no travel', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const year = testInfo.project.name === 'mobile' ? '2008' : '2009';
  const upload = await page.request.post('/api/tracks', {
    multipart: {
      file: {
        name: 'period.gpx',
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk>
          <name>Synthetic period walk ${testInfo.project.name}</name><trkseg>
          <trkpt lat="63.82" lon="20.25"><time>${year}-08-02T08:00:00Z</time></trkpt>
          <trkpt lat="63.821" lon="20.25"><time>${year}-08-02T08:10:00Z</time></trkpt>
          </trkseg></trk></gpx>`),
      },
    },
  });
  expect(upload.status()).toBe(201);
  await expect
    .poll(async () => {
      const response = await page.request.get(
        `/api/heatmap/summary?from=${year}-01-01&to=${year}-12-31`,
      );
      const summary = (await response.json()) as { building: boolean; cells: number };
      return !summary.building && summary.cells > 0;
    })
    .toBe(true);

  const tiles: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/heatmap/tiles/')) tiles.push(request.url());
  });
  await page.goto('/');
  const heatmap = page.getByRole('button', { name: 'Heatmap', exact: true });
  await heatmap.click();
  await expect(page.getByRole('button', { name: 'Date filters: All time' })).toBeVisible();

  await page.getByRole('button', { name: 'Map', exact: true }).click();
  await openDateFilters(page);
  await page.getByRole('combobox', { name: 'Year', exact: true }).selectOption(year);
  await heatmap.click();
  await expect(
    page.getByRole('button', { name: `Date filters: ${year}-01-01 to ${year}-12-31` }),
  ).toBeVisible();
  await expect.poll(() => painted(page)).toBe(true);
  expect(tiles.some((url) => url.endsWith(`?from=${year}-01-01&to=${year}-12-31`))).toBe(true);

  await openDateFilters(page);
  await page.getByLabel('From', { exact: true }).fill('1901-01-01');
  await page.getByLabel('To', { exact: true }).fill('1901-12-31');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'No travel recorded' })).toHaveText(
    'No travel recorded in 1901',
  );
  await expect.poll(() => painted(page)).toBe(false);
  await page.screenshot({
    path: `../../../.impeccable/review/heatmap-empty-${testInfo.project.name}.png`,
  });

  await page.getByRole('button', { name: 'Map', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Date filters: 1901-01-01 to 1901-12-31' }),
  ).toBeVisible();
});
