import { assign, fromPromise, setup } from 'xstate'

import { api } from '../api/client'
import type { Run } from '../api/types'

export type Decision = 'accept' | 'discard'

/**
 * Reviewing an agent's run is a small, irreversible workflow (discard deletes the branch),
 * so it is modelled explicitly instead of a handful of booleans:
 *
 *   idle ──CHOOSE──▶ confirming ──CONFIRM──▶ submitting ──▶ done
 *     ▲                  │ CANCEL                 │ error
 *     └──────────────────┘◀────── failed ◀────────┘ (RETRY / CANCEL)
 */
export const reviewMachine = setup({
  types: {
    context: {} as { runId: string; decision: Decision | null; error: string | null; result: Run | null },
    input: {} as { runId: string },
    events: {} as
      | { type: 'CHOOSE'; decision: Decision }
      | { type: 'CONFIRM' }
      | { type: 'CANCEL' }
      | { type: 'RETRY' },
  },
  actors: {
    submit: fromPromise(({ input }: { input: { runId: string; decision: Decision } }) =>
      input.decision === 'accept' ? api.accept(input.runId) : api.discard(input.runId),
    ),
  },
}).createMachine({
  id: 'review',
  context: ({ input }) => ({ runId: input.runId, decision: null, error: null, result: null }),
  initial: 'idle',
  states: {
    idle: {
      on: { CHOOSE: { target: 'confirming', actions: assign({ decision: ({ event }) => event.decision }) } },
    },
    confirming: {
      on: {
        CONFIRM: 'submitting',
        CANCEL: { target: 'idle', actions: assign({ decision: null }) },
      },
    },
    submitting: {
      invoke: {
        src: 'submit',
        input: ({ context }) => ({ runId: context.runId, decision: context.decision ?? 'accept' }),
        onDone: { target: 'done', actions: assign({ result: ({ event }) => event.output, error: null }) },
        onError: { target: 'failed', actions: assign({ error: ({ event }) => String((event.error as Error)?.message ?? event.error) }) },
      },
    },
    failed: {
      on: {
        RETRY: 'submitting',
        CANCEL: { target: 'idle', actions: assign({ decision: null, error: null }) },
      },
    },
    done: { type: 'final' },
  },
})
