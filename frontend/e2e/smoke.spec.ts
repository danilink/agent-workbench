import { expect, test } from '@playwright/test'

// The core loop end to end: dispatch a task, watch the run finish, read its diff, discard it.
// Discarding at the end also removes the branch and worktree the run created.
test('dispatch → run → diff → discard, without UI errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    // Failed HTTP calls are reported below with their URL, which is more useful than Chrome's generic line.
    if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(`console.error: ${m.text()}`)
  })
  page.on('response', (r) => {
    if (r.status() >= 400) errors.push(`HTTP ${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`)
  })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Agent Workbench' })).toBeVisible()
  await expect(page.getByText('fake', { exact: true })).toBeVisible()

  const prompt = `smoke ${Date.now()}`
  await page.getByPlaceholder(/GET \/api\/version/).fill(prompt)
  await page.getByRole('button', { name: 'Dispatch' }).click()

  const main = page.getByRole('main')
  await expect(main.getByText(prompt)).toBeVisible()
  await expect(main.getByText('Succeeded').first()).toBeVisible({ timeout: 30_000 })
  // FakeRunner emits 7 events: init, text, 2 tool calls, 2 tool results, result.
  await expect(main.getByRole('tab', { name: 'Timeline (7)' })).toBeVisible()
  await expect(main.getByText(/Created NOTES-\w+\.md/).first()).toBeVisible()

  await main.getByRole('tab', { name: 'Diff' }).click()
  await expect(main.getByText(/^\+> smoke \d+$/)).toBeVisible()

  await main.getByRole('button', { name: 'Discard' }).click()
  await main.getByRole('button', { name: 'Yes' }).click()
  await expect(main.getByText('Discarded').first()).toBeVisible()

  expect(errors).toEqual([])
})
