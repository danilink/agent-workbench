import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

const CI = !!process.env.CI
const repoRoot = path.resolve(import.meta.dirname, '..')

// The smoke test drives the real stack with the fake runner: no tokens, but real Play, Postgres and git worktrees.
// Locally both servers are reused if already running. In CI the backend runs from `sbt stage`, because
// `sbt run` stops as soon as stdin is closed.
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  reporter: CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      // The installed Chrome, locally and in CI (GitHub runners ship it): no browser download and no apt step,
      // which failed whenever the runner could not reach the Ubuntu mirrors.
      use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    },
  ],
  webServer: [
    {
      command: CI ? 'target/universal/stage/bin/agent-workbench -Dpidfile.path=/dev/null' : 'sbt run',
      cwd: path.join(repoRoot, 'backend'),
      url: 'http://localhost:9000/api/health',
      // A cheap check, so the smoke test also covers the post-run check.
      env: { WORKBENCH_RUNNER: 'fake', WORKBENCH_REPOS: repoRoot, WORKBENCH_CHECK_COMMAND: 'git status --porcelain' },
      reuseExistingServer: !CI,
      timeout: 300_000,
      stdout: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5173',
      reuseExistingServer: !CI,
      timeout: 60_000,
    },
  ],
})
