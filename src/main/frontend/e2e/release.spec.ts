import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

test('saved controls and reviewed days survive reimport and removal of overlapping exports', async ({
  page,
  browser,
  baseURL,
}, testInfo) => {
  const year = testInfo.project.name === 'mobile' ? '2019' : '2018';
  const sharedName = `Release shared ${year}`;
  const renamed = `Saved release ${year}`;
  const onlyName = `Release collection ${year}`;
  const deletedName = `Release deletion ${year}`;
  const shared = `<trk><name>${sharedName}</name><trkseg>
    <trkpt lat="59.32" lon="18.07"><ele>10</ele><time>${year}-06-18T21:59:00Z</time></trkpt>
    <trkpt lat="59.321" lon="18.071"><time>${year}-06-18T22:00:00Z</time></trkpt>
    <trkpt lat="59.322" lon="18.072"><ele>20</ele><time>${year}-06-18T22:00:00Z</time></trkpt>
    </trkseg><trkseg>
    <trkpt lat="52.52" lon="13.405"><ele>30</ele><time>${year}-06-19T10:00:00Z</time></trkpt>
    </trkseg></trk>`;
  const collection = Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1">${shared}
    <trk><name>${onlyName}</name><trkseg>
    <trkpt lat="59.34" lon="18.04"><time>${year}-06-18T10:00:00Z</time></trkpt>
    <trkpt lat="59.35" lon="18.04"><time>${year}-06-20T10:00:00Z</time></trkpt>
    </trkseg></trk>
    <trk><name>${deletedName}</name><trkseg>
    <trkpt lat="59.34" lon="18.04"><time>${year}-06-18T11:00:00Z</time></trkpt>
    </trkseg></trk></gpx>`);
  const individual = Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1">${shared}</gpx>`);
  const files = [
    { name: `release-collection-${year}.gpx`, mimeType: 'application/gpx+xml', buffer: collection },
    { name: `release-individual-${year}.gpx`, mimeType: 'application/gpx+xml', buffer: individual },
  ];
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  await page.goto('/map');
  if (testInfo.project.name === 'mobile') {
    await expect(page.getByRole('searchbox', { name: 'Search tracks' })).not.toBeVisible();
    await page.getByRole('button', { name: 'Date filters', exact: true }).click();
  }
  await page.getByLabel('From', { exact: true }).fill('1999-01-01');
  await page.getByLabel('To', { exact: true }).fill('1999-01-01');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  await expect(page.getByText('1999-01-01 to 1999-01-01')).toBeVisible();
  await page.getByLabel('Choose GPX file').setInputFiles(files);
  await expect(
    page.getByRole('status').filter({ hasText: '3 imported · 1 duplicate' }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'All time', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  await page.getByRole('button', { name: `Edit ${sharedName}`, exact: true }).click();
  await page.getByLabel('Track name', { exact: true }).fill(renamed);
  await page.getByLabel('Track colour').fill('#654abc');
  await page.getByRole('button', { name: 'Save changes' }).click();
  const coloured = page.locator('.leaflet-overlay-pane path[stroke="#654abc"]');
  await expect(coloured).toHaveCount(2);

  await page.getByLabel('From', { exact: true }).fill(`${year}-06-19`);
  await page.getByLabel('To', { exact: true }).fill(`${year}-06-19`);
  await page.getByRole('button', { name: 'Apply dates' }).click();
  const row = page.getByRole('button', { name: new RegExp(`^${renamed} `) });
  await expect(row).toContainText('3 matching points');
  await row.click();
  const recording = page.getByRole('region', { name: 'Complete recording' });
  await expect(recording).toContainText('4 points · 2 segments');
  await expect(recording).toContainText(`${year}-06-18 23:59:00+02:00`);
  await page.getByRole('slider', { name: 'Elevation plot' }).press('End');
  await expect(page.locator('.playback-marker')).toHaveAttribute(
    'aria-label',
    'Recorded position: 52.52, 13.405',
  );
  await page.getByRole('button', { name: 'Close track' }).click();
  await page.getByRole('checkbox', { name: `Show ${renamed} on map` }).click();
  await expect(coloured).toHaveCount(0);
  await page.getByRole('combobox', { name: 'Year', exact: true }).selectOption(year);
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  for (const day of ['18', '19', '20'])
    await page.getByRole('checkbox', { name: `Select Sweden ${year}-06-${day}` }).check();
  await page.getByRole('button', { name: 'Confirm selected (3)' }).click();
  const summary = page.getByRole('button', { name: 'Explore Sweden' });
  await expect(summary).toContainText('3 recorded days');
  await page.getByRole('checkbox', { name: `Select Germany ${year}-06-19` }).check();
  await page.getByRole('button', { name: 'Exclude selected (1)' }).click();
  await expect(page.getByText('No pending country days in these dates.')).toBeVisible();
  await page.getByLabel('Choose GPX file').setInputFiles(files);
  await expect(
    page.getByRole('status').filter({ hasText: '0 imported · 4 duplicate' }),
  ).toBeVisible();

  const second = await browser.newContext({
    baseURL: baseURL ?? '',
    viewport: { width: 1280, height: 800 },
    isMobile: false,
    hasTouch: false,
  });
  try {
    const other = await second.newPage();
    await other.route('https://tile.openstreetmap.org/**', (route) =>
      route.fulfill({ status: 204 }),
    );
    await other.goto('/map');
    await expect(other.getByRole('checkbox', { name: `Show ${renamed} on map` })).not.toBeChecked();
    await other.getByRole('button', { name: `Edit ${renamed}`, exact: true }).click();
    await expect(other.getByLabel('Track colour')).toHaveValue('#654abc');
    await other.getByRole('button', { name: 'Cancel', exact: true }).click();
    await other.getByRole('combobox', { name: 'Year', exact: true }).selectOption(year);
    await other.getByRole('button', { name: 'Countries', exact: true }).click();
    await expect(other.getByRole('button', { name: 'Explore Sweden' })).toContainText(
      '3 recorded days',
    );
    await other.getByLabel('Review state').selectOption('excluded');
    await expect(other.getByRole('group', { name: `Germany ${year}-06-19` })).toBeVisible();
  } finally {
    await second.close();
  }

  await page.getByRole('button', { name: 'Map', exact: true }).click();
  await page.getByRole('combobox', { name: 'Year', exact: true }).selectOption(year);
  await page.getByRole('button', { name: `Edit ${deletedName}`, exact: true }).click();
  await page.getByRole('button', { name: 'Delete track', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(
    page.getByRole('button', { name: `Edit ${deletedName}`, exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Delete track', exact: true }).click();
  await dialog.getByRole('button', { name: 'Delete track', exact: true }).click();
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  await expect(summary).toContainText('3 recorded days');
  await page.getByRole('button', { name: 'Original exports', exact: true }).click();
  const downloadPromise = page.waitForEvent('download');
  await page
    .getByRole('link', { name: `Download release-collection-${year}.gpx`, exact: true })
    .click();
  expect(await readFile(await (await downloadPromise).path())).toEqual(collection);
  const preview = page.getByRole('button', {
    name: `Preview removal of release-collection-${year}.gpx`,
  });
  await preview.click();
  await expect(dialog.getByRole('region', { name: 'Tracks to remove', exact: true })).toContainText(
    onlyName,
  );
  await expect(
    dialog.getByRole('region', { name: 'Tracks kept through another original' }),
  ).toContainText(renamed);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await preview.click();
  await dialog.getByRole('button', { name: 'Permanently remove file' }).click();
  await expect(preview).not.toBeVisible();
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  await expect(summary).toContainText('2 recorded days');
  await expect(summary).toContainText(`Latest: ${year}-06-19`);
  await page.getByLabel('Review state').selectOption('excluded');
  await expect(page.getByRole('group', { name: `Germany ${year}-06-19` })).toBeVisible();
  await page.getByRole('button', { name: 'Map', exact: true }).click();
  if (testInfo.project.name === 'mobile')
    await page.getByRole('button', { name: 'Show library' }).click();
  const visibility = page.getByRole('checkbox', { name: `Show ${renamed} on map` });
  await expect(visibility).not.toBeChecked();
  await visibility.click();
  await expect(coloured).toHaveCount(2);
  await page.getByRole('button', { name: 'Original exports', exact: true }).click();
  const remaining = page.getByRole('link', {
    name: `Download release-individual-${year}.gpx`,
    exact: true,
  });
  const remainingDownload = page.waitForEvent('download');
  await remaining.click();
  expect(await readFile(await (await remainingDownload).path())).toEqual(individual);
  await page
    .getByRole('button', { name: `Preview removal of release-individual-${year}.gpx` })
    .click();
  await dialog.getByRole('button', { name: 'Permanently remove file' }).click();
  await expect(remaining).not.toBeVisible();
  await page.getByRole('button', { name: 'Countries', exact: true }).click();
  await expect(
    page.getByText('No confirmed visits in these dates. Review the suggestions below to start.'),
  ).toBeVisible();
  await page.getByLabel('Review state').selectOption('excluded');
  await expect(page.getByText('No excluded country days in these dates.')).toBeVisible();
});
