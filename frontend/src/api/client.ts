import type { CreateTaskRequest, Run, RunEvent, TaskDetail, WorkbenchConfig } from './types'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit, as: 'json' | 'text' = 'json'): Promise<T> {
  const res = await fetch(path, {
    ...init,
    // Bypasses Play's CSRF filter for this JSON API; see docs/adr/0002.
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'agent-workbench', ...init?.headers },
  })
  if (!res.ok) {
    const body = await res.text()
    let message = body
    try {
      message = (JSON.parse(body) as { error?: string }).error ?? body
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, message || res.statusText)
  }
  return (as === 'json' ? res.json() : res.text()) as Promise<T>
}

export const api = {
  config: () => request<WorkbenchConfig>('/api/config'),
  tasks: () => request<TaskDetail[]>('/api/tasks'),
  task: (id: string) => request<TaskDetail>(`/api/tasks/${id}`),
  createTask: (body: CreateTaskRequest) =>
    request<TaskDetail>('/api/tasks', { method: 'POST', body: JSON.stringify(body) }),
  events: (runId: string, after = 0) => request<RunEvent[]>(`/api/runs/${runId}/events?after=${after}`),
  diff: (runId: string) => request<string>(`/api/runs/${runId}/diff`, undefined, 'text'),
  cancel: (runId: string) => request<Run>(`/api/runs/${runId}/cancel`, { method: 'POST' }),
  accept: (runId: string) => request<Run>(`/api/runs/${runId}/accept`, { method: 'POST' }),
  discard: (runId: string) => request<Run>(`/api/runs/${runId}/discard`, { method: 'POST' }),
}

export const keys = {
  config: ['config'] as const,
  tasks: ['tasks'] as const,
  task: (id: string) => ['task', id] as const,
  events: (runId: string) => ['events', runId] as const,
  diff: (runId: string) => ['diff', runId] as const,
}
