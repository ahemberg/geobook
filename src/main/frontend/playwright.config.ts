import { defineConfig, devices } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const externalURL = process.env['GEOBOOK_TEST_URL'];
const baseURL = externalURL ?? 'http://127.0.0.1:18080';
const loginURL = 'http://127.0.0.1:18081';
const jar = 'java -jar ../../../target/tracker-0.0.1-SNAPSHOT.jar';
const dataDirectory = () => mkdtempSync(join(tmpdir(), 'geobook-browser-'));
const login = /(login|account)\.spec\.ts/;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  use: { baseURL, trace: 'retain-on-failure' },
  projects: [
    {
      name: 'desktop',
      testIgnore: login,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    { name: 'mobile', testIgnore: login, use: { ...devices['Pixel 7'] } },
    // The packaged jar runs a second time in password mode; a container under test is public.
    ...(externalURL
      ? []
      : [
          {
            name: 'login',
            testMatch: login,
            use: { ...devices['Desktop Chrome'], baseURL: loginURL },
          },
        ]),
  ],
  webServer: externalURL
    ? []
    : [
        {
          command: `${jar} --server.port=18080 --GEOBOOK_AUTH_MODE=public --geobook.data-directory=${dataDirectory()}`,
          url: `${baseURL}/api/tracks`,
          reuseExistingServer: false,
          timeout: 60000,
        },
        {
          command: `${jar} --server.port=18081 --GEOBOOK_OWNER_PASSWORD=browser-journey --geobook.data-directory=${dataDirectory()}`,
          url: `${loginURL}/api/status`,
          reuseExistingServer: false,
          timeout: 60000,
        },
      ],
});
