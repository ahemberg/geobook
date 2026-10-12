import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

test('download, cancel and confirm track and whole-file removal with shared sources', async ({
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
  const label = testInfo.project.name;
  const latitude = label === 'mobile' ? '57.31' : '57.30';
  const sharedName = `Shared waterfront ${label}`;
  const onlyName = `Collection walk ${label}`;
  const deletedName = `Library deletion ${label}`;
  const recording = (name: string, longitude: string) =>
    `<trk><name>${name}</name><trkseg><trkpt lat="${latitude}" lon="${longitude}"/></trkseg></trk>`;
  const shared = recording(sharedName, '18.11');
  const gpx = (tracks: string) =>
    Buffer.from(`<gpx xmlns="http://www.topografix.com/GPX/1/1">${tracks}</gpx>`);
  const collection = gpx(shared + recording(onlyName, '18.12') + recording(deletedName, '18.13'));
  const individual = gpx(shared);
  const collectionFile = `removal-collection-${label}.gpx`;
  const individualFile = `removal-individual-${label}.gpx`;
  await page.goto('/');
  const paths = page.locator('.leaflet-overlay-pane path');
  await expect(page.getByRole('region', { name: 'Recorded travel map' })).toBeVisible();
  await expect(page.getByText('Loading your tracks…')).not.toBeVisible();
  const before = await paths.count();
  await page.getByLabel('Choose GPX file').setInputFiles([
    { name: collectionFile, mimeType: 'application/gpx+xml', buffer: collection },
    { name: individualFile, mimeType: 'application/gpx+xml', buffer: individual },
  ]);
  await expect(
    page.getByRole('status').filter({ hasText: '3 imported · 1 duplicate' }),
  ).toBeVisible();
  await expect(paths).toHaveCount(before + 3);
  if (label === 'mobile') await page.getByRole('button', { name: 'Show library' }).click();
  await page.getByRole('button', { name: `Edit ${sharedName}`, exact: true }).click();
  await page.getByLabel('Track colour').fill('#a10203');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.locator('.leaflet-overlay-pane path[stroke="#a10203"]')).toHaveCount(1);
  await page.getByRole('button', { name: new RegExp(`^${deletedName} .*recorded points`) }).click();
  await expect(page.getByRole('heading', { name: deletedName })).toBeVisible();
  await page.getByRole('button', { name: `Edit ${deletedName}`, exact: true }).click();
  await page.getByRole('button', { name: 'Delete track', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Original exports will be kept');
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Delete track', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Delete track', exact: true }).click();
  await dialog.getByRole('button', { name: 'Delete track', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).not.toBeVisible();
  await expect(paths).toHaveCount(before + 2);
  await page.getByRole('button', { name: 'Original exports', exact: true }).click();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('link', { name: `Download ${collectionFile}`, exact: true }).click();
  const download = await downloadPromise;
  expect(await readFile(await download.path())).toEqual(collection);
  await page.screenshot({ path: testInfo.outputPath('archive.png'), fullPage: true });
  const preview = page.getByRole('button', { name: `Preview removal of ${collectionFile}` });
  await preview.click();
  dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('region', { name: 'Tracks to remove', exact: true })).toContainText(
    onlyName,
  );
  await expect(
    dialog.getByRole('region', { name: 'Tracks kept through another original' }),
  ).toContainText(sharedName);
  await expect(dialog).not.toContainText(deletedName);
  await page.screenshot({ path: testInfo.outputPath('removal-preview.png'), fullPage: true });
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await expect(preview).toBeFocused();
  await expect(page.getByRole('link', { name: `Download ${collectionFile}` })).toBeVisible();
  await preview.click();
  await dialog.getByRole('button', { name: 'Permanently remove file' }).click();
  await expect(page.getByRole('link', { name: `Download ${collectionFile}` })).not.toBeVisible();
  const remainingLink = page.getByRole('link', { name: `Download ${individualFile}` });
  expect(
    await (await page.request.get((await remainingLink.getAttribute('href')) ?? '')).body(),
  ).toEqual(individual);
  await page.getByRole('button', { name: 'Map', exact: true }).click();
  await expect(paths).toHaveCount(before + 1);
  await expect(page.locator('.leaflet-overlay-pane path[stroke="#a10203"]')).toHaveCount(1);
  if (label === 'mobile') await page.getByRole('button', { name: 'Show library' }).click();
  await expect(
    page.getByRole('button', { name: `Edit ${onlyName}`, exact: true }),
  ).not.toBeVisible();
  await expect(page.getByRole('button', { name: `Edit ${sharedName}`, exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Original exports', exact: true }).click();
  await page.getByRole('button', { name: `Preview removal of ${individualFile}` }).click();
  await expect(
    page.getByRole('dialog').getByRole('region', { name: 'Tracks to remove', exact: true }),
  ).toContainText(sharedName);
  await page.getByRole('dialog').getByRole('button', { name: 'Permanently remove file' }).click();
  await expect(remainingLink).not.toBeVisible();
  await page.getByRole('button', { name: 'Map', exact: true }).click();
  await expect(paths).toHaveCount(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
