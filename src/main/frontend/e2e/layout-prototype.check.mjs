// Run against npm run prototype. Reads live tracks; all edits remain in the browser.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from '@playwright/test';

const evidence = new URL('../../../../.impeccable/review/layout-prototype/', import.meta.url);
await mkdir(evidence, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1366, height: 768 } });
  const errors = [];
  const writes = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('request', (request) => {
    if (request.url().includes('/api/') && !['GET', 'HEAD'].includes(request.method())) {
      writes.push(request.method());
    }
  });
  await page.goto('http://localhost:5174/?variant=A');
  await page.locator('.prototype-track-select').first().waitFor();
  const mapBefore = await page.locator('.map-panel').boundingBox();
  const list = page.locator('.prototype-list-scroll');
  await list.evaluate((element) => {
    element.scrollTop = 600;
  });
  assert.deepEqual(
    await page.locator('.map-panel').boundingBox(),
    mapBefore,
    'Library scrolling moved map',
  );
  assert.equal(await page.evaluate(() => scrollY), 0);
  await list.evaluate((element) => {
    element.scrollTop = 0;
  });
  await page.locator('.prototype-track-select').first().click();
  await page.locator('.compact-recording').waitFor();
  const plot = page.getByRole('slider', { name: 'Elevation plot' });
  await plot.focus();
  await page.keyboard.press('ArrowRight');
  assert.match(page.url(), /variant=A/);
  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await page.waitForTimeout(300);
  await page.getByRole('button', { name: 'Pause', exact: true }).click();
  const selected = await page.locator('.compact-recording-heading h2').textContent();
  const reading = await page.locator('.compact-point-reading').textContent();
  for (const variant of ['A', 'B', 'C']) {
    if (variant !== 'A') await page.getByRole('button', { name: 'Next layout' }).click();
    await page.waitForTimeout(250);
    assert.equal(await page.locator('.compact-recording-heading h2').textContent(), selected);
    assert.equal(await page.locator('.compact-point-reading').textContent(), reading);
    const geometry = await page.evaluate(() => ({
      height: document.documentElement.scrollHeight,
      width: document.documentElement.scrollWidth,
      viewportHeight: innerHeight,
      viewportWidth: innerWidth,
      plotBottom: document.querySelector('.elevation-plot').getBoundingClientRect().bottom,
    }));
    assert.equal(geometry.height, geometry.viewportHeight);
    assert.equal(geometry.width, geometry.viewportWidth);
    assert.ok(geometry.plotBottom < geometry.viewportHeight - 68);
    await page.screenshot({ path: new URL(`${variant}-desktop.png`, evidence).pathname });
  }
  const visibility = page.locator('.prototype-track-row input[type="checkbox"]').first();
  const wasVisible = await visibility.isChecked();
  await visibility.setChecked(!wasVisible);
  assert.equal(await visibility.isChecked(), !wasVisible);
  await page.reload();
  await visibility.waitFor();
  assert.equal(await visibility.isChecked(), wasVisible, 'Preview changed persisted visibility');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Tracks', exact: true }).click();
  await list.evaluate((element) => {
    element.scrollTop = 500;
  });
  const row = page.locator('.prototype-track-select').nth(10);
  await row.scrollIntoViewIfNeeded();
  const scrollBefore = await list.evaluate((element) => element.scrollTop);
  await row.click();
  await page.locator('.compact-recording').waitFor();
  assert.equal(
    await page.getByRole('button', { name: 'Tracks', exact: true }).getAttribute('aria-expanded'),
    'false',
  );
  assert.equal(await page.evaluate(() => document.activeElement?.textContent), 'Tracks');
  await page.getByRole('button', { name: 'Tracks', exact: true }).click();
  assert.equal(await list.evaluate((element) => element.scrollTop), scrollBefore);
  await page.getByRole('button', { name: 'Tracks', exact: true }).click();
  for (const variant of ['A', 'B', 'C']) {
    await page.getByRole('button', { name: 'Next layout' }).click();
    await page.waitForTimeout(250);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
    assert.equal(await page.evaluate(() => document.documentElement.scrollHeight), 844);
    await page.screenshot({ path: new URL(`${variant}-mobile.png`, evidence).pathname });
  }
  await page.locator('.prototype-dates > summary').click();
  await page.getByLabel('From', { exact: true }).fill('2026-01-01');
  await page.getByLabel('To', { exact: true }).fill('2026-12-31');
  await page.getByRole('button', { name: 'Apply dates' }).click();
  assert.equal(await page.locator('.prototype-dates').evaluate((element) => element.open), false);
  assert.equal(
    await page.evaluate(() => document.documentElement.scrollWidth),
    390,
    'Date summary overflow',
  );
  await page.locator('.prototype-dates > summary').click();
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('.prototype-dates').evaluate((element) => element.open), false);
  assert.deepEqual(writes, [], 'Prototype sent a mutation');
  const blocked = await page.request.post('http://localhost:5174/api/prototype-write-check');
  assert.equal(blocked.status(), 405, 'Prototype proxy allowed a write');
  assert.deepEqual(errors, []);
  console.log(
    'Passed: desktop/mobile fit, independent scroll, playback, variant state, mobile focus/scroll restoration, date panel, temporary edits and write guard.',
  );
} finally {
  await browser.close();
}
