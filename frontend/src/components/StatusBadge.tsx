import { Badge, Spinner, HStack } from '@chakra-ui/react'

import type { CheckStatus, ReviewState, RunStatus } from '../api/types'

const statusColor: Record<RunStatus, string> = {
  Queued: 'gray',
  Preparing: 'purple',
  Running: 'blue',
  Succeeded: 'green',
  Failed: 'red',
  Cancelled: 'orange',
}

export function StatusBadge({ status }: { status: RunStatus }) {
  const busy = status === 'Running' || status === 'Preparing'
  return (
    <HStack spacing={1}>
      {busy && <Spinner size="xs" />}
      <Badge colorScheme={statusColor[status]}>{status}</Badge>
    </HStack>
  )
}

const checkLabel: Record<CheckStatus, { text: string; color: string }> = {
  Running: { text: 'Check…', color: 'blue' },
  Passed: { text: 'Check passed', color: 'green' },
  Failed: { text: 'Check failed', color: 'red' },
}

export function CheckBadge({ status }: { status: CheckStatus }) {
  const { text, color } = checkLabel[status]
  return (
    <HStack spacing={1}>
      {status === 'Running' && <Spinner size="xs" />}
      <Badge variant="subtle" colorScheme={color}>
        {text}
      </Badge>
    </HStack>
  )
}

export function ReviewBadge({ review }: { review: ReviewState }) {
  if (review === 'Pending') return null
  return (
    <Badge variant="outline" colorScheme={review === 'Accepted' ? 'green' : 'gray'}>
      {review}
    </Badge>
  )
}
