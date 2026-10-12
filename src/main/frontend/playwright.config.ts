import { defineConfig, devices } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const externalURL = process.env['GEOBOOK_TEST_URL'];
const baseURL = externalURL ?? 'http://127.0.0.1:18080';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  use: { baseURL, trace: 'retain-on-failure' },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: externalURL
    ? []
    : {
        command: `java -jar ../../../target/tracker-0.0.1-SNAPSHOT.jar --server.port=18080 --geobook.data-directory=${mkdtempSync(join(tmpdir(), 'geobook-browser-'))}`,
        url: `${baseURL}/api/tracks`,
        reuseExistingServer: false,
        timeout: 60000,
      },
});
