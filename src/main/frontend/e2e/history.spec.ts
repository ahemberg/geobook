import { gzipSync } from 'node:zlib';
import { expect, test } from '@playwright/test';
import { openDateFilters, openLibrary } from './workspaceControls';

function takeout(date: string) {
  const json = Buffer.from(
    JSON.stringify({
      timelineObjects: [
        {
          placeVisit: {
            location: { name: 'Synthetic station', latitudeE7: 593200000, longitudeE7: 180700000 },
            duration: { startTimestamp: `${date}T10:00:00Z`, endTimestamp: `${date}T11:00:00Z` },
          },
        },
      ],
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
  const recorded = info.project.name === 'mobile' ? '2025-06-19' : '2025-06-18';
  const name = `18 Jun ${date.slice(0, 4)} · Synthetic station`;
  await page.request.post('/api/imports', {
    multipart: {
      file: {
        name: `synthetic-recording-${info.project.name}.gpx`,
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(
          `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Waterfront ${info.project.name}</name><trkseg><trkpt lat="59.32" lon="18.07"><time>${recorded}T10:00:00Z</time></trkpt><trkpt lat="59.321" lon="18.071"><time>${recorded}T10:01:00Z</time></trkpt></trkseg></trk></gpx>`,
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
  await expect(
    page.getByRole('status').filter({ hasText: 'Google history: 1 created' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Date filters: All time', exact: true }),
  ).toBeVisible();
  await openLibrary(page);
  const history = page.getByRole('region', { name: 'Google history' });
  await expect(history.getByRole('heading', { name: 'Google history' })).toBeVisible();
  await expect(history.getByText('Inferred')).toBeVisible();
  const yearGroup = history.getByRole('group', { name: `${year} history days` });
  const openYear = async () => {
    if ((await yearGroup.getAttribute('open')) === null) await yearGroup.locator('summary').click();
  };
  await openYear();
  await expect(history.getByTitle(name)).toBeVisible();
  await expect(history.getByTitle(name)).toContainText('Synthetic station');
  await expect(history.getByTitle(name)).toContainText(date);
  const search = page.getByRole('searchbox', { name: 'Search tracks' });
  await search.fill('station');
  await openYear();
  await expect(history.getByTitle(name)).toBeVisible();
  await search.fill(date);
  await openYear();
  await expect(history.getByTitle(name)).toBeVisible();
  await search.clear();
  await openYear();
  const allHistory = history.getByRole('switch', { name: 'Show Google history on map' });
  const yearGate = history.getByRole('checkbox', { name: `Show ${year} history on map` });
  const dayGate = history.getByRole('checkbox', { name: `Show ${name} on map` });
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
    reloadedHistory.getByRole('checkbox', { name: `Show ${name} on map` }),
  ).not.toBeChecked();
  await reloadedHistory.getByRole('switch').click();
  await expect(reloadedHistory.getByRole('switch')).toBeChecked();
  await reloadedHistory.getByRole('checkbox', { name: `Show ${year} history on map` }).click();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${year} history on map` }),
  ).toBeChecked();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${name} on map` }),
  ).not.toBeChecked();
  await reloadedHistory.getByRole('checkbox', { name: `Show ${name} on map` }).click();
  await expect(
    reloadedHistory.getByRole('checkbox', { name: `Show ${name} on map` }),
  ).toBeChecked();
  await expect(page.locator('.leaflet-history-pane canvas')).toBeAttached();
  await expect(page.getByRole('region', { name: 'Google history' }).getByTitle(name)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath('history.png'), fullPage: true });

  await expect(page.locator('.history-stop')).toHaveCount(0);
  await page.getByRole('region', { name: 'Google history' }).getByTitle(name).click();
  const day = page.getByRole('region', { name: 'History day' });
  await expect(day.getByRole('heading', { name: /18 Jun \d{4} Google history/ })).toBeVisible();
  await expect(day.locator('.history-span')).toHaveText('12:00–13:00');
  await expect(day.locator('.history-facts')).toContainText('2 fixes kept · 0 dropped');
  await expect(day.getByRole('button', { name: /play/i })).toHaveCount(0);
  if (info.project.name === 'mobile')
    await expect(page.getByRole('button', { name: 'Show library' })).toBeVisible();
  const stop = page.locator('.history-stop').first();
  await expect(stop).toBeVisible();
  await stop.click();
  await expect(page.locator('.leaflet-tooltip')).toHaveText('Synthetic station · 12:00–13:00');
  await day.getByRole('button', { name: /Synthetic station/ }).click();
  await expect(stop).toBeInViewport();
  await day.getByText('Day details').click();
  await expect(day.getByText(/Inferred from Google location history/)).toBeVisible();
  expect(
    await (
      await page.request.get(
        (await day.getByRole('link', { name: 'Download Takeout archive' }).getAttribute('href')) ??
          '',
      )
    ).body(),
  ).toEqual(original);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath('history-day.png') });

  await openLibrary(page);
  await page.getByRole('button', { name: new RegExp(`^Waterfront ${info.project.name}`) }).click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toBeVisible();
  await expect(day).toHaveCount(0);
  await expect(page.locator('.history-stop')).toHaveCount(0);
  await openLibrary(page);
  await openYear();
  await page.getByRole('region', { name: 'Google history' }).getByTitle(name).click();
  await expect(day).toBeVisible();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toHaveCount(0);
});
