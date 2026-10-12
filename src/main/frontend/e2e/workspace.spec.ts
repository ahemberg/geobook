import { expect, test } from '@playwright/test';
import { openDateFilters, openLibrary } from './workspaceControls';

test('the docked workspace contains library scrolling, playback and mobile selection', async ({
  page,
}, testInfo) => {
  if (testInfo.project.name === 'desktop') await page.setViewportSize({ width: 1366, height: 768 });
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const longitude = testInfo.project.name === 'mobile' ? 18.03 : 18.02;
  const tracks = Array.from(
    { length: 24 },
    (
      _,
      index,
    ) => `<trk><name>12 oktober 2026 · Layout ${testInfo.project.name} ${index}</name><trkseg>
    <trkpt lat="${59.3 + index / 1000}" lon="${longitude}"><ele>10</ele><time>2026-10-12T10:00:00Z</time></trkpt>
    <trkpt lat="${59.31 + index / 1000}" lon="${longitude}"><ele>30</ele><time>2026-10-12T10:02:00Z</time></trkpt>
    </trkseg></trk>`,
  ).join('');
  const imported = await page.request.post('/api/imports', {
    multipart: {
      file: {
        name: 'layout.gpx',
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1">${tracks}</gpx>`),
      },
    },
  });
  expect(imported.ok()).toBe(true);
  await page.goto('/');
  await openLibrary(page);
  await page
    .getByRole('searchbox', { name: 'Search tracks' })
    .fill(`Layout ${testInfo.project.name}`);
  const rows = page.locator('.track-row');
  await expect(rows).toHaveCount(24);
  await expect(rows.first()).not.toContainText('UTC');
  await expect(rows.first()).not.toContainText('points');
  await expect(rows.first()).not.toContainText('Open complete recording');
  const list = page.locator('.track-list');
  const map = page.locator('.map-panel');
  const before = await map.boundingBox();
  await list.evaluate((element) => {
    element.scrollTop = 500;
  });
  expect(await map.boundingBox()).toEqual(before);
  const row = rows.nth(10);
  await row.scrollIntoViewIfNeeded();
  const scroll = await list.evaluate((element) => element.scrollTop);
  await row.click();
  const plot = page.getByRole('slider', { name: 'Elevation plot' });
  await expect(plot).toBeInViewport();
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeInViewport();
  const mapBounds = await map.boundingBox();
  const plotBounds = await plot.boundingBox();
  expect(mapBounds && plotBounds && mapBounds.y + mapBounds.height <= plotBounds.y).toBe(true);
  expect(
    await page.evaluate(() => ({
      width: document.documentElement.scrollWidth - innerWidth,
      height: document.documentElement.scrollHeight - innerHeight,
    })),
  ).toEqual({ width: 0, height: 0 });
  if (testInfo.project.name === 'mobile') {
    await expect(page.getByRole('button', { name: 'Show library' })).toBeFocused();
    await openLibrary(page);
    expect(await list.evaluate((element) => element.scrollTop)).toBe(scroll);
    await expect(plot).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Play', exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: 'Hide library' }).click();
    await expect(plot).toBeVisible();
  }
  await plot.press('End');
  await expect(page.locator('.point-reading')).toContainText('30 m');
  await openDateFilters(page);
  await page.getByLabel('From', { exact: true }).fill('2026-10-12');
  await page.getByLabel('To', { exact: true }).fill('2026-10-12');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  await expect(page.getByRole('button', { name: /^Date filters:/ })).toBeFocused();
  await expect(page.getByRole('button', { name: /^Date filters:/ })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
    await page.evaluate(() => innerWidth),
  );
});
