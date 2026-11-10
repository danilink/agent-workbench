import { Badge, Spinner, HStack } from '@chakra-ui/react'

import type { ReviewState, RunStatus } from '../api/types'

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

export function ReviewBadge({ review }: { review: ReviewState }) {
  if (review === 'Pending') return null
  return (
    <Badge variant="outline" colorScheme={review === 'Accepted' ? 'green' : 'gray'}>
      {review}
    </Badge>
  )
}
