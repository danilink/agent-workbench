import { Button, HStack, Text } from '@chakra-ui/react'
import { useMachine } from '@xstate/react'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'

import { keys } from '../api/client'
import { reviewMachine } from '../machines/reviewMachine'

export function ReviewBar({ runId }: { runId: string }) {
  const qc = useQueryClient()
  const [state, send] = useMachine(reviewMachine, { input: { runId } })
  const { decision, error, result } = state.context

  useEffect(() => {
    if (result) void qc.invalidateQueries({ queryKey: keys.task(result.taskId) })
  }, [result, qc])

  if (state.matches('idle'))
    return (
      <HStack spacing={2}>
        <Button size="xs" colorScheme="green" onClick={() => send({ type: 'CHOOSE', decision: 'accept' })}>
          Accept
        </Button>
        <Button size="xs" variant="outline" onClick={() => send({ type: 'CHOOSE', decision: 'discard' })}>
          Discard
        </Button>
      </HStack>
    )

  if (state.matches('confirming'))
    return (
      <HStack spacing={2}>
        <Text fontSize="xs">
          {decision === 'accept' ? 'Commit the changes to the branch and remove the worktree?' : 'Delete the branch, the worktree and every change in it?'}
        </Text>
        <Button size="xs" colorScheme={decision === 'accept' ? 'green' : 'red'} onClick={() => send({ type: 'CONFIRM' })}>
          Yes
        </Button>
        <Button size="xs" variant="ghost" onClick={() => send({ type: 'CANCEL' })}>
          No
        </Button>
      </HStack>
    )

  if (state.matches('submitting')) return <Button size="xs" isLoading>Working</Button>

  if (state.matches('failed'))
    return (
      <HStack spacing={2}>
        <Text fontSize="xs" color="red.400">{error}</Text>
        <Button size="xs" onClick={() => send({ type: 'RETRY' })}>Retry</Button>
        <Button size="xs" variant="ghost" onClick={() => send({ type: 'CANCEL' })}>Back</Button>
      </HStack>
    )

  return <Text fontSize="xs" color="gray.500">{decision === 'accept' ? 'Accepted' : 'Discarded'}</Text>
}
