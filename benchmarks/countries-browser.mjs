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
  "Usage: node benchmarks/countries-browser.mjs URL MANIFEST [RUNS]",
);
assert(
  Number.isInteger(Number(runs)) && Number(runs) > 0,
  "RUNS must be a positive integer",
);
const workload = JSON.parse(readFileSync(manifestPath, "utf8"));
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
    page.on("pageerror", (error) => errors.push(String(error)));
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
    // This benchmark changes reviews. Run only against the isolated synthetic collection.
    const response = await context.request.get(url + "/api/visits", {
      timeout: 120000,
    });
    assert(response.ok(), "Could not read synthetic country days");
    const { candidates, unresolvedPoints } = await response.json();
    assert.equal(unresolvedPoints, 0);
    assert.equal(candidates.length, workload.tracks);
    assert(
      candidates.every(
        (candidate) =>
          candidate.country === "SWE" &&
          candidate.tracks.length === 1 &&
          /^Benchmark \d{4}$/.test(candidate.tracks[0].name),
      ),
    );
    const visits = candidates.map(({ country, date }) => ({ country, date }));
    const restore = async () => {
      const saved = await context.request.patch(url + "/api/visits/review", {
        data: { state: "confirmed", visits },
        timeout: 120000,
      });
      assert(saved.ok(), "Could not confirm synthetic country days");
    };
    await restore();
    try {
      const painted = () =>
        page.evaluate(
          () =>
            new Promise((resolve) =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
        );
      await page.goto(url, { timeout: 120000 });
      await expect(page.locator(".track-count")).toHaveText(
        `Tracks: ${workload.tracks}`,
      );
      let started = performance.now();
      await page
        .getByRole("button", { name: "Countries", exact: true })
        .click();
      const summary = page.getByRole("button", { name: "Explore Sweden" });
      await expect(summary).toContainText(`${workload.tracks} recorded days`);
      await expect(
        page.getByText("No pending country days in these dates."),
      ).toBeVisible();
      await painted();
      const countryLoadMs = performance.now() - started;

      started = performance.now();
      await page.getByLabel("Review state").selectOption("confirmed");
      const rows = page
        .getByRole("main", { name: "Country visits" })
        .getByRole("group");
      await expect(rows).toHaveCount(workload.tracks);
      await painted();
      const reviewRowsMs = performance.now() - started;
      await page
        .getByRole("button", { name: "Select up to 1000 days" })
        .click();
      started = performance.now();
      await page
        .getByRole("button", { name: `Exclude selected (${workload.tracks})` })
        .click();
      await expect(
        page.getByRole("status").filter({
          hasText: `${workload.tracks} country days saved as excluded.`,
        }),
      ).toBeVisible();
      await expect(
        page.getByText(
          "No confirmed visits in these dates. Review the suggestions below to start.",
        ),
      ).toBeVisible();
      await expect(
        page.getByText("No confirmed country days in these dates."),
      ).toBeVisible();
      await expect(rows).toHaveCount(0);
      await painted();
      const save500Ms = performance.now() - started;
      await page.getByLabel("Review state").selectOption("excluded");
      await expect(rows).toHaveCount(workload.tracks);
      await restore();
      await page.getByRole("button", { name: "Map", exact: true }).click();
      await page
        .getByRole("button", { name: "Countries", exact: true })
        .click();
      await expect(summary).toContainText(`${workload.tracks} recorded days`);
      started = performance.now();
      await summary.click();
      await expect(
        page.getByRole("button", { name: "All countries", exact: true }),
      ).toBeVisible();
      await expect(page.locator(".leaflet-overlay-pane path")).toHaveCount(
        workload.tracks * 3,
      );
      await painted();
      const exploreCountryMs = performance.now() - started;
      assert.deepEqual(errors, []);
      const result = {
        run: run + 1,
        countryLoadMs,
        reviewRowsMs,
        save500Ms,
        exploreCountryMs,
        withinTargets: countryLoadMs < 5000 && save500Ms < 2000,
      };
      results.push(result);
      console.log(JSON.stringify(result));
    } finally {
      await restore();
      await context.close();
    }
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
          "Fresh contexts, external tiles stubbed, no CPU/network throttling",
      },
      results,
    }),
  );
  if (results.some((result) => !result.withinTargets)) process.exitCode = 1;
} finally {
  await browser.close();
}
