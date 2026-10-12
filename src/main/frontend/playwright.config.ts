import { defineConfig, devices } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dataDirectory = mkdtempSync(join(tmpdir(), 'geobook-browser-'));

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:18080', trace: 'retain-on-failure' },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: `java -jar ../../../target/tracker-0.0.1-SNAPSHOT.jar --server.port=18080 --geobook.data-directory=${dataDirectory}`,
    url: 'http://127.0.0.1:18080/api/tracks',
    reuseExistingServer: false,
    timeout: 60000,
  },
});
