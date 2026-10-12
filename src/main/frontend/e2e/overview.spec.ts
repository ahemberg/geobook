import { expect, test } from '@playwright/test';

test('compact overviews retain breaks, filtered counts and complete selected recordings', async ({
  page,
}, testInfo) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.fulfill({ status: 204 }));
  const name = `Dense overview ${testInfo.project.name}`;
  const latitude = testInfo.project.name === 'mobile' ? '59.321' : '59.32';
  const points = Array.from({ length: 1000 }, (_, index) => {
    const day = index < 500 ? '01' : '02';
    const hour = index < 750 ? '10' : '11';
    const split = index === 500 ? '</trkseg><trkseg>' : '';
    return `${split}<trkpt lat="${latitude}" lon="${(18.07 + index / 10000000).toFixed(7)}"><time>2023-03-${day}T${hour}:00:00Z</time></trkpt>`;
  }).join('');
  const uploaded = await page.request.post('/api/tracks', {
    multipart: {
      file: {
        name: 'dense.gpx',
        mimeType: 'application/gpx+xml',
        buffer: Buffer.from(
          `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${name}</name><trkseg>${points}</trkseg></trk></gpx>`,
        ),
      },
    },
  });
  expect(uploaded.status()).toBe(201);
  const { trackId } = (await uploaded.json()) as { trackId: number };
  const overview = (await (
    await page.request.get(`/api/tracks?search=${encodeURIComponent(name)}`)
  ).json()) as { pointCount: number; geometry: number[][][] }[];
  expect(overview[0]?.pointCount).toBe(1000);
  expect(overview[0]?.geometry).toHaveLength(3);
  expect(overview[0]?.geometry.flat().length).toBeLessThan(100);
  const detailRequests: string[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === `/api/tracks/${trackId}`)
      detailRequests.push(request.url());
  });
  await page.goto('/');
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('button', { name: 'Show library' }).click();
    await page.getByRole('button', { name: 'Date filters' }).click();
  }
  const row = page.getByRole('button', { name: new RegExp(`^${name} `) });
  await expect(row).toContainText('1,000 recorded points');
  expect(detailRequests).toHaveLength(0);
  await page.getByLabel('From', { exact: true }).fill('2023-03-02');
  await page.getByLabel('To', { exact: true }).fill('2023-03-02');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  await expect(row).toContainText('500 matching points');
  expect(detailRequests).toHaveLength(0);
  await row.click();
  await expect(page.getByRole('region', { name: 'Complete recording' })).toContainText(
    '1,000 points · 2 segments',
  );
  expect(detailRequests).toHaveLength(1);
  await page.getByLabel('Point', { exact: true }).fill('1000');
  await expect(page.getByRole('region', { name: 'Complete recording' })).toContainText(
    '18.0700999',
  );
  await page.getByRole('button', { name: 'Close track' }).click();
  await expect(row).toContainText('500 matching points');
});
