import { expect, test, type Page } from '@playwright/test';
import { duration } from '../app/heatScale';
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

test('the heatmap shows an update status while rebuilding and reloads its tiles after', async ({
  page,
}) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  let first = true;
  await page.route(/\/api\/heatmap\/summary/, async (route) => {
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
  expect(tiles.some((url) => url.endsWith(`?from=${year}-01-01&to=${year}-12-31&limit=15`))).toBe(
    true,
  );

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

test('the daily limit and scale change the legend, and the scale redraws without refetching', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const longitude = testInfo.project.name === 'mobile' ? '11.97' : '11.98';
  const upload = await page.request.post('/api/tracks', {
    multipart: {
      file: {
        name: 'limit.gpx',
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk>
          <name>Synthetic long stay ${testInfo.project.name}</name><trkseg>
          <trkpt lat="57.70" lon="${longitude}"><time>2020-03-02T08:00:00Z</time></trkpt>
          <trkpt lat="57.7001" lon="${longitude}"><time>2020-03-02T18:00:00Z</time></trkpt>
          <trkpt lat="57.7002" lon="${longitude}"><time>2020-03-02T18:05:00Z</time></trkpt>
          </trkseg></trk></gpx>`),
      },
    },
  });
  expect(upload.status()).toBe(201);
  const globalFull = async (limit: string) => {
    const response = await page.request.get(`/api/heatmap/summary?limit=${limit}`);
    return ((await response.json()) as { building: boolean; globalFull: number }).globalFull;
  };
  await expect
    .poll(async () => {
      const response = await page.request.get('/api/heatmap/summary');
      return ((await response.json()) as { building: boolean }).building;
    })
    .toBe(false);

  const tiles: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/heatmap/tiles/')) tiles.push(request.url());
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Heatmap', exact: true }).click();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Limit and scale' }).click();
  const limit = page.getByRole('radiogroup', { name: 'Daily limit' });
  const scale = page.getByRole('radiogroup', { name: 'Scale' });
  for (const option of ['None', '60 min', '15 min', '5 min']) {
    await expect(limit.getByText(option, { exact: true })).toBeVisible();
  }
  await expect(limit.getByRole('radio', { name: '15 min' })).toBeChecked();
  await expect(scale.getByRole('radio', { name: 'On screen' })).toBeChecked();
  await expect(scale.getByRole('radio', { name: 'Global' })).not.toBeChecked();
  await expect(page.getByRole('button', { name: 'Date filters: All time' })).toBeVisible();
  const legend = page.getByRole('group', { name: 'Colour legend' });
  await expect(legend).toHaveText(/^Full colour at ≥ \d+(\.\d)? (min|h)$/);
  await expect.poll(() => tiles.length).toBeGreaterThan(0);
  expect(tiles.every((url) => url.endsWith('?limit=15'))).toBe(true);

  await page.waitForTimeout(300);
  const loaded = tiles.length;
  await scale.getByRole('radio', { name: 'Global' }).click();
  await expect(scale.getByRole('radio', { name: 'Global' })).toBeChecked();
  await expect(legend).toHaveText(`Full colour at ≥ ${duration(await globalFull('15'))}`);
  expect(tiles).toHaveLength(loaded);

  await limit.getByRole('radio', { name: 'None' }).click();
  const unlimited = `Full colour at ≥ ${duration(await globalFull('0'))}`;
  expect(unlimited).not.toBe(`Full colour at ≥ ${duration(await globalFull('15'))}`);
  await expect(legend).toHaveText(unlimited);
  await expect.poll(() => tiles.some((url) => url.endsWith('?limit=0'))).toBe(true);

  await page.keyboard.press('ArrowRight');
  await expect(limit.getByRole('radio', { name: '60 min' })).toBeChecked();
  await expect(legend).toHaveText(`Full colour at ≥ ${duration(await globalFull('60'))}`);
  await page.screenshot({
    path: `../../../.impeccable/review/heatmap-controls-${testInfo.project.name}.png`,
  });
});

test('the heatmap keeps position, zoom, limit and scale in the URL across a reload', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const mobile = testInfo.project.name === 'mobile';
  const upload = await page.request.post('/api/tracks', {
    multipart: {
      file: {
        name: 'url.gpx',
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk>
          <name>Synthetic URL walk ${testInfo.project.name}</name><trkseg>
          <trkpt lat="55.60" lon="${mobile ? '13.00' : '13.01'}"><time>2022-04-02T08:00:00Z</time></trkpt>
          <trkpt lat="55.601" lon="${mobile ? '13.00' : '13.01'}"><time>2022-04-02T08:10:00Z</time></trkpt>
          </trkseg></trk></gpx>`),
      },
    },
  });
  expect(upload.status()).toBe(201);
  await expect
    .poll(async () => {
      const response = await page.request.get('/api/heatmap/summary');
      return ((await response.json()) as { building: boolean }).building;
    })
    .toBe(false);
  await page.goto('/');
  await page.getByRole('button', { name: 'Heatmap', exact: true }).click();
  await expect(page).toHaveURL(/\/heatmap\?at=-?\d+\.\d{5},-?\d+\.\d{5},13$/);

  if (mobile) await page.getByRole('button', { name: 'Limit and scale' }).click();
  await page.getByRole('radio', { name: '60 min' }).click();
  await page.getByRole('radio', { name: 'Global' }).click();
  await page.locator('.heatmap-panel .leaflet-container').focus();
  await page.keyboard.press('Minus');
  await page.keyboard.press('ArrowRight');
  await expect(page).toHaveURL(/at=-?\d+\.\d{5},-?\d+\.\d{5},12&limit=60&scale=global$/);
  const view = page.url();

  await page.reload();
  await expect(page.getByRole('button', { name: 'Heatmap', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  if (mobile) await page.getByRole('button', { name: 'Limit and scale' }).click();
  await expect(page.getByRole('radio', { name: '60 min' })).toBeChecked();
  await expect(page.getByRole('radio', { name: 'Global' })).toBeChecked();
  await expect(page.getByRole('group', { name: 'Colour legend' })).toBeVisible();
  expect(page.url()).toBe(view);

  const beijing = '/heatmap?at=39.90420,116.40740,14';
  await page.goto(beijing);
  await expect(page.getByRole('radio', { name: '15 min', includeHidden: true })).toBeChecked();
  await expect(page.getByRole('status').filter({ hasText: 'No travel recorded' })).toBeHidden();
  await page.waitForTimeout(300);
  expect(page.url()).toMatch(/\/heatmap\?at=39\.90420,116\.40740,14$/);

  await page.getByRole('button', { name: 'Map', exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Recorded travel' })).toBeVisible();
});

test('the heatmap controls sit behind a toggle on mobile and stay visible on desktop', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  await page.goto('/heatmap');
  const toggle = page.getByRole('button', { name: 'Limit and scale' });
  const limit = page.getByRole('radiogroup', { name: 'Daily limit' });
  const scale = page.getByRole('radiogroup', { name: 'Scale' });
  await expect(page.getByRole('button', { name: 'Date filters: All time' })).toBeVisible();
  if (testInfo.project.name !== 'mobile') {
    await expect(toggle).toBeHidden();
    await expect(limit).toBeVisible();
    await expect(scale).toBeVisible();
    return;
  }

  await expect(limit).toBeHidden();
  await expect(scale).toBeHidden();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(toggle).toHaveAttribute('aria-controls', 'heat-options');
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(limit).toBeVisible();
  await expect(scale).toBeVisible();
  await expect(page.locator('#heat-options')).toBeVisible();
  await page.screenshot({ path: '../../../.impeccable/review/heatmap-options-mobile.png' });
  await page.keyboard.press('Space');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(limit).toBeHidden();
});
