import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { expect, test, type Page } from '@playwright/test'

const repoRoot = path.resolve(import.meta.dirname, '../..')
const git = (...args: string[]) => execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' })

/** Fails on any page error, console error or HTTP >= 400 seen during the test. */
function collectErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    // Failed HTTP calls are reported below with their URL, which is more useful than Chrome's generic line.
    if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(`console.error: ${m.text()}`)
  })
  page.on('response', (r) => {
    if (r.status() >= 400) errors.push(`HTTP ${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`)
  })
  return errors
}

/** Dispatches a one-attempt task and waits for the fake run to succeed. Returns the run's branch. */
async function dispatchAndFinish(page: Page, prompt: string): Promise<string> {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Agent Workbench' })).toBeVisible()
  // Guard: against a backend running the real runner this test would start a real, paid agent session.
  await expect(
    page.getByText('fake', { exact: true }),
    'backend must run with WORKBENCH_RUNNER=fake (stop the real one, or let Playwright start its own)',
  ).toBeVisible()

  await page.getByPlaceholder(/GET \/api\/version/).fill(prompt)
  await page.getByRole('button', { name: 'Dispatch' }).click()

  const main = page.getByRole('main')
  await expect(main.getByText(prompt)).toBeVisible()
  await expect(main.getByText('Succeeded').first()).toBeVisible({ timeout: 30_000 })
  return (await main.getByText(/^wb\/[0-9a-f]{8}-v1$/).textContent()) ?? ''
}

// The core loop end to end: dispatch a task, watch the run finish, read its diff, discard it.
// Discarding at the end also removes the branch and worktree the run created.
test('dispatch → run → diff → discard, without UI errors', async ({ page }) => {
  const errors = collectErrors(page)
  const prompt = `smoke ${Date.now()}`
  await dispatchAndFinish(page, prompt)

  const main = page.getByRole('main')
  // FakeRunner emits 7 events: init, text, 2 tool calls, 2 tool results, result.
  await expect(main.getByRole('tab', { name: 'Timeline (7)' })).toBeVisible()
  await expect(main.getByText(/Created NOTES-\w+\.md/).first()).toBeVisible()

  // The configured check (`git status --porcelain`, see playwright.config.ts) runs after the agent succeeds.
  await expect(main.getByText('Check passed')).toBeVisible({ timeout: 30_000 })
  await main.getByRole('tab', { name: 'Check' }).click()
  await expect(main.getByText(/exit 0/)).toBeVisible()

  await main.getByRole('tab', { name: 'Diff' }).click()
  await expect(main.getByText(/^\+> smoke \d+$/)).toBeVisible()

  await main.getByRole('button', { name: 'Discard' }).click()
  await main.getByRole('button', { name: 'Yes' }).click()
  await expect(main.getByText('Discarded').first()).toBeVisible()

  expect(errors).toEqual([])
})

// Agents rarely commit. Accept used to remove the worktree with the work still uncommitted in it, leaving an
// empty branch behind.
test('accept keeps the agent\'s uncommitted work on the run\'s branch', async ({ page }) => {
  const errors = collectErrors(page)
  const branch = await dispatchAndFinish(page, `accept ${Date.now()}`)
  expect(branch).toMatch(/^wb\//)

  const main = page.getByRole('main')
  await main.getByRole('button', { name: 'Accept' }).click()
  await main.getByRole('button', { name: 'Yes' }).click()
  await expect(main.getByText('Accepted').first()).toBeVisible()

  try {
    const files = git('ls-tree', '--name-only', branch).split('\n')
    expect(files.some((f) => /^NOTES-\w+\.md$/.test(f)), `files on ${branch}: ${files.join(', ')}`).toBe(true)
  } finally {
    git('branch', '-D', branch)
  }
  expect(errors).toEqual([])
})
