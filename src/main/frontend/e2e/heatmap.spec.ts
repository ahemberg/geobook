import { expect, test, type Page } from '@playwright/test';

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

test('the heatmap shows an update status while rebuilding and reloads its tiles after', async ({
  page,
}) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  let first = true;
  await page.route('**/api/heatmap/summary', async (route) => {
    const response = await route.fetch();
    const summary = (await response.json()) as Record<string, unknown>;
    const building = first;
    first = false;
    await route.fulfill({
      response,
      json: { ...summary, building, builtAt: summary.builtAt ?? '2026-01-01T00:00:00Z' },
    });
  });
  const tiles: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/heatmap/tiles/')) tiles.push(request.url());
  });

  await page.goto('/');
  await page.getByRole('button', { name: 'Heatmap', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Updating heatmap…' })).toBeVisible();
  await expect.poll(() => tiles.length).toBeGreaterThan(0);
  const loaded = [...tiles];

  await expect(page.getByText('Updating heatmap…')).toBeHidden();
  await expect
    .poll(() => loaded.every((url) => tiles.indexOf(url) !== tiles.lastIndexOf(url)))
    .toBe(true);
});
