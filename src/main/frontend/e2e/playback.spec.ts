import { openDateFilters } from './workspaceControls';
import { expect, test } from '@playwright/test';

test('full recording playback and touch scrubbing keep the manual camera fixed', async ({
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
  await page.request.patch('/api/playback-settings', {
    data: { gapSeconds: 300, showGapConnections: false },
  });
  const mobile = testInfo.project.name === 'mobile';
  const name = `Synthetic replay ${testInfo.project.name}`;
  const longitude = mobile ? 18.08 : 18.07;
  const times = [0, 10, 10, 20, 3600, 3610];
  const original = Buffer.from(
    `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${name}</name><trkseg>${times
      .map(
        (seconds, index) => `<trkpt lat="${59.32 + index * 0.1}" lon="${longitude}">
      ${index === 1 ? '' : `<ele>${10 + index * 5}</ele>`}
      <time>${new Date(Date.UTC(2026, 5, 18, 21, 59, 40 + seconds)).toISOString()}</time></trkpt>`,
      )
      .join('')}</trkseg></trk></gpx>`,
  );
  await page.goto('/');
  await page
    .getByLabel('Choose GPX file')
    .setInputFiles({ name: 'replay.gpx', mimeType: 'application/gpx+xml', buffer: original });
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await openDateFilters(page);
  await page.getByLabel('From', { exact: true }).fill('2026-06-19');
  await page.getByLabel('To', { exact: true }).fill('2026-06-19');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  if (mobile) await page.getByRole('button', { name: 'Show library' }).click();
  const row = page.getByRole('button', { name: new RegExp(`^${name} `) });
  await expect(row).toBeVisible();
  await row.click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toContainText('6 points');
  await expect(page.locator('.playback-marker')).toHaveAttribute(
    'aria-label',
    `Recorded position: 59.32, ${longitude}`,
  );
  await expect(page.locator('.recording-gap')).toHaveCount(0);

  await page.getByText('Recording gaps', { exact: true }).click();
  await page.getByLabel('Show gray dashed gap connections').check();
  await page.getByRole('button', { name: 'Save gap settings' }).click();
  await expect(page.locator('.recording-gap')).not.toHaveCount(0);
  expect(await (await page.request.get('/api/playback-settings')).json()).toEqual({
    gapSeconds: 300,
    showGapConnections: true,
  });
  await page.getByText('Recording gaps', { exact: true }).click();

  await page.getByText('Point details', { exact: true }).click();
  await page.clock.install({ time: new Date('2026-06-20T00:00:00Z') });
  await page.clock.pauseAt(new Date('2026-06-20T00:00:01Z'));
  const map = page.getByLabel('Interactive map');
  await map.scrollIntoViewIfNeeded();
  for (let index = 0; index < 4; index++) {
    await page.getByRole('button', { name: 'Zoom in' }).click();
    await page.clock.runFor(300);
  }
  await expect(page.locator('.leaflet-zoom-anim')).toHaveCount(0);
  const mapBox = await map.boundingBox();
  if (!mapBox) throw new Error('Map has no bounds');
  await page.mouse.move(mapBox.x + mapBox.width / 2, mapBox.y + mapBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(mapBox.x + mapBox.width / 2 + 90, mapBox.y + mapBox.height / 2 + 60, {
    steps: 5,
  });
  await page.mouse.up();
  // Finish manual pan inertia before measuring camera stability.
  await page.clock.runFor(1000);
  const camera = () =>
    map.evaluate((element) => ({
      pane: element.querySelector('.leaflet-map-pane')?.getAttribute('style'),
      paths: Array.from(element.querySelectorAll('.leaflet-overlay-pane path')).map((path) =>
        path.getAttribute('d'),
      ),
      viewBox: element.querySelector('.leaflet-overlay-pane svg')?.getAttribute('viewBox'),
    }));
  const before = await camera();
  const plot = page.getByRole('slider', { name: 'Elevation plot' });
  await plot.scrollIntoViewIfNeeded();
  const plotBox = await plot.boundingBox();
  if (!plotBox) throw new Error('Plot has no bounds');
  if (mobile) {
    const session = await page.context().newCDPSession(page);
    const y = plotBox.y + plotBox.height / 2;
    await session.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [{ x: plotBox.x + 1, y }],
    });
    await session.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: plotBox.x + plotBox.width + 5, y }],
    });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await session.detach();
  } else {
    await page.mouse.move(plotBox.x + 1, plotBox.y + 50);
    await page.mouse.down();
    await page.mouse.move(plotBox.x + plotBox.width + 5, plotBox.y + 50, { steps: 4 });
    await page.mouse.up();
  }
  await expect(page.getByRole('spinbutton', { name: 'Point', exact: true })).toHaveValue('6');
  await expect(page.locator('.playback-marker')).toHaveAttribute(
    'aria-label',
    `Recorded position: 59.82, ${longitude}`,
  );
  expect(await camera()).toEqual(before);
  const offscreen = await page.locator('.playback-marker').evaluate((marker) => {
    const point = marker.getBoundingClientRect();
    const bounds = marker.closest('.map')?.getBoundingClientRect();
    return (
      bounds &&
      (point.right < bounds.left ||
        point.left > bounds.right ||
        point.bottom < bounds.top ||
        point.top > bounds.bottom)
    );
  });
  expect(offscreen).toBe(true);
  await plot.press('Home');
  await page.getByRole('combobox', { name: 'Playback speed' }).selectOption('100');
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.clock.runFor(500);
  await expect(page.getByRole('spinbutton', { name: 'Point', exact: true })).toHaveValue('6');
  await expect(page.getByText(/Missing recording · 00:59:40 skipped/)).toBeVisible();
  expect(await camera()).toEqual(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({
    path: `../../../.impeccable/review/${testInfo.project.name}.png`,
    fullPage: true,
  });
  await page.request.patch('/api/playback-settings', {
    data: { gapSeconds: 300, showGapConnections: false },
  });
});
