import {
  chromium,
  expect as baseExpect,
} from "../src/main/frontend/node_modules/@playwright/test/index.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import os from "node:os";

const [url, manifestPath, runs = "5"] = process.argv.slice(2);
assert(
  url && manifestPath,
  "Usage: node benchmarks/heatmap-browser.mjs URL MANIFEST [RUNS]",
);
assert(
  Number.isInteger(Number(runs)) && Number(runs) > 0,
  "RUNS must be a positive integer",
);
const workload = JSON.parse(readFileSync(manifestPath, "utf8"));
const year = workload.from.slice(0, 4);
const yearQuery = `?from=${year}-01-01&to=${year}-12-31`;
const expect = baseExpect.configure({ timeout: 120000 });
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
    const tiles = [];
    page.on("pageerror", (error) => errors.push(String(error)));
    page.on("response", (response) => {
      if (new URL(response.url()).pathname.startsWith("/api/heatmap/tiles/"))
        tiles.push({ url: response.url(), status: response.status() });
    });
    const config = await (
      await context.request.get(url + "/api/map-config", { timeout: 120000 })
    ).json();
    await page.route(new URL(config.url).origin + "/**", (route) =>
      route.fulfill({
        contentType: "image/png",
        body: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
          "base64",
        ),
      }),
    );
    const summaryFor = (query) =>
      page.waitForResponse((response) => {
        const address = new URL(response.url());
        return (
          address.pathname === "/api/heatmap/summary" &&
          address.search === query
        );
      });
    // The new layer replaces the old one before its summary is requested, so once that
    // summary has arrived every canvas in the heat pane belongs to the new period.
    async function drawn(summaryResponse, query) {
      const summary = await (await summaryResponse).json();
      assert.equal(summary.building, false, "The heatmap was still building");
      assert(summary.cells > 0, `No heat for ${query || "all time"}`);
      await page.waitForFunction(() => {
        const canvases = [
          ...document.querySelectorAll(".leaflet-heat-pane canvas"),
        ];
        return (
          canvases.length > 0 &&
          canvases.every((canvas) =>
            canvas.classList.contains("leaflet-tile-loaded"),
          ) &&
          canvases.some((canvas) =>
            canvas
              .getContext("2d")
              .getImageData(0, 0, canvas.width, canvas.height)
              .data.some((value, index) => index % 4 === 3 && value > 0),
          )
        );
      });
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      const loaded = tiles.filter((tile) => new URL(tile.url).search === query);
      assert(loaded.length > 0, `No heat tiles for ${query || "all time"}`);
      assert(
        loaded.every((tile) => tile.status === 200),
        "A heat tile failed",
      );
      return {
        cells: summary.cells,
        tiles: loaded.length,
        canvases: await page.locator(".leaflet-heat-pane canvas").count(),
      };
    }
    const heatmap = page.getByRole("button", { name: "Heatmap", exact: true });
    const firstSummary = summaryFor("");
    const started = performance.now();
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 120000 });
    await heatmap.click();
    const clicked = performance.now();
    const allTime = await drawn(firstSummary, "");
    const firstLoadMs = performance.now() - started;
    const openMs = performance.now() - clicked;
    await expect(heatmap).toHaveAttribute("aria-current", "page");

    await page.getByRole("button", { name: /^Date filters:/ }).click();
    const yearSummary = summaryFor(yearQuery);
    const yearStarted = performance.now();
    await page
      .getByRole("combobox", { name: "Year", exact: true })
      .selectOption(year);
    const yearHeat = await drawn(yearSummary, yearQuery);
    const periodMs = performance.now() - yearStarted;
    await expect(
      page.getByRole("button", {
        name: `Date filters: ${year}-01-01 to ${year}-12-31`,
      }),
    ).toBeVisible();

    await page.getByRole("button", { name: /^Date filters:/ }).click();
    const allSummary = summaryFor("");
    const allStarted = performance.now();
    await page.getByRole("button", { name: "All time", exact: true }).click();
    await drawn(allSummary, "");
    const allTimeMs = performance.now() - allStarted;
    assert.deepEqual(errors, []);
    const result = {
      run: run + 1,
      firstLoadMs,
      openMs,
      periodMs,
      allTimeMs,
      withinTargets: periodMs < 2000 && allTimeMs < 2000,
      allTimeCells: allTime.cells,
      yearCells: yearHeat.cells,
      firstLoadTiles: allTime.tiles,
      heatCanvases: allTime.canvases,
    };
    results.push(result);
    console.log(JSON.stringify(result));
    await context.close();
  }
  console.log(
    JSON.stringify({
      url,
      workload,
      period: yearQuery,
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
