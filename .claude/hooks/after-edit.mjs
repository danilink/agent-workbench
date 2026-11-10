#!/usr/bin/env node
// PostToolUse hook: after an agent edits a frontend TS file, type-check the frontend.
// Exit code 2 sends stderr back to Claude so it fixes the error in the same turn.
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const input = JSON.parse(readFileSync(0, 'utf8'))
const file = input?.tool_input?.file_path ?? ''
const root = process.env.CLAUDE_PROJECT_DIR ?? process.cwd()

if (!/\/frontend\/src\/.*\.(ts|tsx)$/.test(file)) process.exit(0)

try {
  execFileSync('npm', ['run', '--silent', 'typecheck'], { cwd: path.join(root, 'frontend'), stdio: 'pipe' })
} catch (e) {
  const out = `${e.stdout ?? ''}${e.stderr ?? ''}`.trim()
  process.stderr.write(`Frontend typecheck failed after editing ${path.relative(root, file)}:\n${out}\n`)
  process.exit(2)
}
