import { gzipSync } from 'node:zlib';
import { expect, test } from '@playwright/test';
import { openDateFilters, openLibrary } from './workspaceControls';

function takeout(date: string) {
  const json = Buffer.from(
    JSON.stringify({
      locations: [
        { latitudeE7: 593200000, longitudeE7: 180700000, timestamp: `${date}T10:00:00Z` },
        { latitudeE7: 593210000, longitudeE7: 180710000, timestamp: `${date}T10:01:00Z` },
      ],
    }),
  );
  const header = Buffer.alloc(512);
  header.write('beliebiger-ordner/renamed-data');
  const octal = (value: number, start: number, length: number) =>
    header.write(`${value.toString(8).padStart(length - 1, '0')}\0`, start, length);
  octal(0o644, 100, 8);
  octal(0, 108, 8);
  octal(0, 116, 8);
  octal(json.length, 124, 12);
  octal(0, 136, 12);
  header.fill(' ', 148, 156);
  header.write('0', 156);
  header.write('ustar\0', 257);
  header.write('00', 263);
  octal(
    header.reduce((sum, byte) => sum + byte, 0),
    148,
    7,
  );
  header[155] = 32;
  return gzipSync(
    Buffer.concat([header, json, Buffer.alloc(((512 - (json.length % 512)) % 512) + 1024)]),
  );
}

test('Takeout appears as inferred history below tracks on desktop and mobile', async ({
  page,
}, info) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const date = info.project.name === 'mobile' ? '2012-06-18' : '2013-06-18';
  const year = Number(date.slice(0, 4));
  const original = takeout(date);
  await page.request.post('/api/imports', {
    multipart: {
      file: {
        name: `synthetic-recording-${info.project.name}.gpx`,
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(
          `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Waterfront ${info.project.name}</name><trkseg><trkpt lat="59.32" lon="18.07"><time>2025-06-18T10:00:00Z</time></trkpt><trkpt lat="59.321" lon="18.071"><time>2025-06-18T10:01:00Z</time></trkpt></trkseg></trk></gpx>`,
        ),
      },
    },
  });
  await page.goto('/');
  await openDateFilters(page);
  await page.getByLabel('From', { exact: true }).fill('2025-01-01');
  await page.getByLabel('To', { exact: true }).fill('2025-12-31');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  await page.getByLabel('Choose GPX or Takeout').setInputFiles({
    name: 'synthetic-history.tgz',
    mimeType: 'application/gzip',
    buffer: original,
  });
  await expect(page.getByRole('status').filter({ hasText: '1 imported' })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Date filters: All time', exact: true }),
  ).toBeVisible();
  await openLibrary(page);
  const history = page.getByRole('region', { name: 'Google history' });
  await expect(history.getByRole('heading', { name: 'Google history' })).toBeVisible();
  await expect(history.getByText('Inferred')).toBeVisible();
  const yearGroup = history.getByRole('group', { name: `${year} history days` });
  if ((await yearGroup.getAttribute('open')) === null) await yearGroup.locator('summary').click();
  await expect(history.getByText(date, { exact: true })).toBeVisible();
  const allHistory = history.getByRole('switch', { name: 'Show Google history on map' });
  const yearGate = history.getByRole('checkbox', { name: `Show ${year} history on map` });
  const dayGate = history.getByRole('checkbox', { name: `Show ${date} on map` });
  await dayGate.click();
  await expect(dayGate).not.toBeChecked();
  await yearGate.click();
  await expect(yearGate).not.toBeChecked();
  await allHistory.click();
  await expect(allHistory).not.toBeChecked();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toHaveCount(0);

  expect(
    await page
      .locator('.leaflet-history-pane')
      .evaluate((element) => getComputedStyle(element).zIndex),
  ).toBe('350');
  await page.getByText('Review import results').click();
  const link = page.getByRole('link', { name: 'Download original export: synthetic-history.tgz' });
  expect(await (await page.request.get((await link.getAttribute('href')) ?? '')).body()).toEqual(
    original,
  );
  await page.getByText('Review import results').click();
  await page.reload();
  await openLibrary(page);
  const reloadedHistory = page.getByRole('region', { name: 'Google history' });
  const reloadedGroup = reloadedHistory.getByRole('group', { name: `${year} history days` });
  if ((await reloadedGroup.getAttribute('open')) === null)
    await reloadedGroup.locator('summary').click();
  await expect(reloadedHistory.getByRole('switch')).not.toBeChecked();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${year} history on map` }),
  ).not.toBeChecked();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${date} on map` }),
  ).not.toBeChecked();
  await reloadedHistory.getByRole('switch').click();
  await expect(reloadedHistory.getByRole('switch')).toBeChecked();
  await reloadedHistory.getByRole('checkbox', { name: `Show ${year} history on map` }).click();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${year} history on map` }),
  ).toBeChecked();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${date} on map` }),
  ).not.toBeChecked();
  await reloadedHistory.getByRole('checkbox', { name: `Show ${date} on map` }).click();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${date} on map` }),
  ).toBeChecked();
  await expect(page.locator('.leaflet-history-pane canvas')).toBeAttached();
  await expect(
    page.getByRole('region', { name: 'Google history' }).getByText(date, { exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath('history.png'), fullPage: true });
});
