import { useEffect, useState } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'

import { keys } from './client'
import { TERMINAL, type Run, type RunEvent, type TaskDetail } from './types'

export type StreamState = 'connecting' | 'open' | 'closed'

/**
 * Subscribes once to /api/stream and folds pushes into the React Query cache.
 * REST is the source of truth; the stream only keeps already-loaded data fresh, so a
 * reconnect simply invalidates everything and lets queries refetch.
 */
export function useWorkbenchStream(): StreamState {
  const qc = useQueryClient()
  const [state, setState] = useState<StreamState>('connecting')

  useEffect(() => {
    const es = new EventSource('/api/stream')
    es.onopen = () => {
      setState('open')
      void qc.invalidateQueries()
    }
    es.onerror = () => setState(es.readyState === EventSource.CLOSED ? 'closed' : 'connecting')
    es.addEventListener('run', (e) => applyRun(qc, JSON.parse((e as MessageEvent<string>).data) as Run))
    es.addEventListener('run-event', (e) =>
      applyEvent(qc, JSON.parse((e as MessageEvent<string>).data) as RunEvent),
    )
    return () => es.close()
  }, [qc])

  return state
}

function upsertRun(detail: TaskDetail, run: Run): TaskDetail {
  const exists = detail.runs.some((r) => r.id === run.id)
  const runs = exists ? detail.runs.map((r) => (r.id === run.id ? run : r)) : [...detail.runs, run]
  return { ...detail, runs: runs.sort((a, b) => a.variant - b.variant) }
}

function applyRun(qc: QueryClient, run: Run) {
  qc.setQueryData<TaskDetail>(keys.task(run.taskId), (d) => (d ? upsertRun(d, run) : d))
  qc.setQueryData<TaskDetail[]>(keys.tasks, (ts) =>
    ts?.map((d) => (d.task.id === run.taskId ? upsertRun(d, run) : d)),
  )
  // A finished run's diff is final now; make sure we don't show a mid-run snapshot. Refetching events
  // also closes the small gap where an event lands between the initial fetch and the cache existing.
  if (TERMINAL.has(run.status)) {
    void qc.invalidateQueries({ queryKey: keys.diff(run.id) })
    void qc.invalidateQueries({ queryKey: keys.events(run.id) })
  }
}

function applyEvent(qc: QueryClient, ev: RunEvent) {
  qc.setQueryData<RunEvent[]>(keys.events(ev.runId), (evs) => {
    if (!evs) return evs // not being viewed; it'll be fetched on demand
    if (evs.some((e) => e.seq === ev.seq)) return evs
    return [...evs, ev].sort((a, b) => a.seq - b.seq)
  })
}
