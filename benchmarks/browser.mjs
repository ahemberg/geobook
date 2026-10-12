import {
  chromium,
  expect,
} from "../src/main/frontend/node_modules/@playwright/test/index.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import os from "node:os";

const [url, manifestPath, runs = "5"] = process.argv.slice(2);
assert(
  url && manifestPath,
  "Usage: node benchmarks/browser.mjs URL MANIFEST [RUNS]",
);
assert(
  Number.isInteger(Number(runs)) && Number(runs) > 0,
  "RUNS must be a positive integer",
);
const workload = JSON.parse(readFileSync(manifestPath, "utf8"));
const browser = await chromium.launch({ headless: true });
const results = [];
try {
  for (let run = 0; run < Number(runs); run++) {
    const context = await browser.newContext({
      viewport: { width: 1440, height: 900 },
    });
    const page = await context.newPage();
    page.setDefaultTimeout(120000);
    const errors = [];
    const requests = [];
    page.on("pageerror", (error) => errors.push(String(error)));
    page.on("request", (request) =>
      requests.push(new URL(request.url()).pathname),
    );
    const config = await (
      await context.request.get(url + "/api/map-config")
    ).json();
    const tileOrigin = new URL(config.url).origin;
    await page.route(tileOrigin + "/**", (route) =>
      route.fulfill({
        contentType: "image/png",
        body: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
          "base64",
        ),
      }),
    );
    async function painted(trackCount) {
      await expect(page.locator(".track-count")).toHaveText(
        `Tracks: ${trackCount}`,
        { timeout: 120000 },
      );
      await expect(page.locator(".leaflet-overlay-pane path")).toHaveCount(
        trackCount * 3,
        { timeout: 120000 },
      );
      await expect(
        page.locator(".leaflet-overlay-pane path").first(),
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Fit all tracks" }),
      ).toBeEnabled();
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
    }
    const started = performance.now();
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 120000 });
    await painted(workload.tracks);
    const overviewMs = performance.now() - started;
    assert(
      !requests.some((path) => /^\/api\/tracks\/\d+$/.test(path)),
      "Overview fetched complete recordings",
    );
    await page.getByLabel("From", { exact: true }).fill(workload.from);
    await page.getByLabel("To", { exact: true }).fill(workload.to);
    const filterStarted = performance.now();
    await page.getByRole("button", { name: "Apply dates" }).click();
    await painted(workload.filteredTracks);
    const filterMs = performance.now() - filterStarted;
    const row = page.getByRole("button", {
      name: new RegExp(`^${workload.filteredName} `),
    });
    await expect(row).toContainText(
      `${workload.filteredPoints.toLocaleString("en-US")} matching points`,
    );
    const allStarted = performance.now();
    await page.getByRole("button", { name: "All time", exact: true }).click();
    await painted(workload.tracks);
    const allTimeMs = performance.now() - allStarted;
    const response = await context.request.get(url + "/api/tracks");
    const body = await response.body();
    const tracks = JSON.parse(body);
    assert.equal(
      tracks.reduce((count, track) => count + track.pointCount, 0),
      workload.points,
    );
    assert.deepEqual(errors, []);
    const result = {
      run: run + 1,
      overviewMs,
      filterMs,
      allTimeMs,
      overviewBytes: body.length,
      withinTargets: overviewMs < 5000 && filterMs < 2000 && allTimeMs < 2000,
      overviewVertices: tracks.reduce(
        (count, track) => count + track.geometry.flat().length,
        0,
      ),
    };
    if (run === 0) {
      await page
        .getByRole("searchbox", { name: "Search tracks" })
        .fill(workload.filteredName);
      await row.click();
      await expect(
        page.getByRole("region", { name: "Complete recording" }),
      ).toContainText(
        `Complete recording · ${workload.pointsPerTrack.toLocaleString("en-US")} points`,
      );
    }
    results.push(result);
    console.log(JSON.stringify(result));
    await context.close();
  }
  console.log(
    JSON.stringify({
      url,
      workload,
      client: {
        platform: os.platform(),
        arch: os.arch(),
        cpu: os.cpus()[0].model,
        memoryBytes: os.totalmem(),
        chromium: browser.version(),
        viewport: "1440x900",
        conditions:
          "Headless Chromium, fresh context per run, external tiles stubbed, no CPU/network throttling",
      },
      results,
    }),
  );
  if (results.some((result) => !result.withinTargets)) process.exitCode = 1;
} finally {
  await browser.close();
}
