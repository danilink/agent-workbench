// Mirrors backend/app/workbench/domain/Model.scala + api/JsonFormats.scala.
// play-json omits None fields, hence the optional properties.

export type RunStatus = 'Queued' | 'Preparing' | 'Running' | 'Succeeded' | 'Failed' | 'Cancelled'
export type ReviewState = 'Pending' | 'Accepted' | 'Discarded'
export type EventKind = 'Init' | 'Text' | 'ToolUse' | 'ToolResult' | 'Result' | 'Log' | 'Unknown'

export const TERMINAL: ReadonlySet<RunStatus> = new Set(['Succeeded', 'Failed', 'Cancelled'])

export interface Task {
  id: string
  repoPath: string
  baseRef: string
  baseSha: string
  prompt: string
  createdAt: string
}

export interface Run {
  id: string
  taskId: string
  variant: number
  status: RunStatus
  branch: string
  worktreePath: string
  claudeSessionId?: string
  costUsd?: number
  numTurns?: number
  resultSummary?: string
  error?: string
  review: ReviewState
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface TaskDetail {
  task: Task
  runs: Run[]
}

export type EventPayload =
  | { kind: 'Init'; payload: { sessionId: string; model?: string; cwd?: string } }
  | { kind: 'Text'; payload: { text: string } }
  | { kind: 'ToolUse'; payload: { id: string; name: string; input: unknown } }
  | { kind: 'ToolResult'; payload: { toolUseId: string; isError: boolean; preview: string } }
  | {
      kind: 'Result'
      payload: { sessionId?: string; isError: boolean; costUsd?: number; numTurns?: number; summary?: string }
    }
  | { kind: 'Log'; payload: { stream: string; line: string } }
  | { kind: 'Unknown'; payload: { raw: unknown } }

export type RunEvent = { runId: string; seq: number; at: string } & EventPayload

export interface WorkbenchConfig {
  repos: string[]
  runner: string
  maxParallelRuns: number
  maxVariants: number
}

export interface CreateTaskRequest {
  repoPath: string
  prompt: string
  baseRef?: string
  variants: number
}
