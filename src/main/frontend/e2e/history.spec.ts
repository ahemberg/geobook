import { gzipSync } from 'node:zlib';
import { openLibrary } from './workspaceControls';
import { expect, test } from '@playwright/test';

// A minimal ustar archive, gzipped like a Takeout .tgz export.
function takeout(files: Record<string, string>) {
  const blocks: Buffer[] = [];
  for (const [name, content] of Object.entries(files)) {
    const body = Buffer.from(content);
    const header = Buffer.alloc(512);
    header.write(name, 0);
    header.write('0000644\0', 100);
    header.write('0000000\0', 108);
    header.write('0000000\0', 116);
    header.write(`${body.length.toString(8).padStart(11, '0')}\0`, 124);
    header.write('00000000000\0', 136);
    header.write('        ', 148);
    header.write('0', 156);
    header.write('ustar\u000000', 257);
    const checksum = header.reduce((sum, byte) => sum + byte, 0);
    header.write(`${checksum.toString(8).padStart(6, '0')}\0 `, 148);
    blocks.push(header, body, Buffer.alloc((512 - (body.length % 512)) % 512));
  }
  return gzipSync(Buffer.concat([...blocks, Buffer.alloc(1024)]));
}

test('a Takeout archive becomes inferred history days under the tracks', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const year = testInfo.project.name === 'mobile' ? 2015 : 2014;
  // Synthetic Stockholm fixes on both sides of local midnight.
  const locations = [
    [`${year}-06-18T20:00:00Z`, 593200000, 180600000],
    [`${year}-06-18T21:00:00Z`, 593210000, 180610000],
    [`${year}-06-18T22:10:00Z`, 593300000, 180700000],
    [`${year}-06-18T23:10:00Z`, 593310000, 180710000],
  ].map(([timestamp, latitudeE7, longitudeE7]) => ({
    timestamp,
    latitudeE7,
    longitudeE7,
    accuracy: 20,
  }));
  const archive = takeout({
    'Takeout/Location History/Records.json': JSON.stringify({ locations }),
    'Takeout/Location History/Semantic Location History/2014/2014_JUNE.json': JSON.stringify({
      timelineObjects: [],
    }),
  });

  await page.goto('/');
  await page
    .getByLabel('Choose GPX file')
    .setInputFiles({ name: 'takeout.tgz', mimeType: 'application/gzip', buffer: archive });
  await expect(page.getByRole('status').filter({ hasText: '2 imported' })).toBeVisible();
  await openLibrary(page);
  const history = page.getByRole('region', { name: 'Google history' });
  await expect(history).toContainText('Inferred');
  await expect(history.getByText(`${year}-06-18`, { exact: true })).toBeVisible();
  await expect(history.getByText(`${year}-06-19`, { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toHaveCount(0);
  await expect(page.locator('.leaflet-history-pane canvas')).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});
