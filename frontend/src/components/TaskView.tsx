import { useState } from 'react'
import { Box, Code, Heading, HStack, SimpleGrid, Stack, Text } from '@chakra-ui/react'
import { useQuery } from '@tanstack/react-query'

import { api, keys } from '../api/client'
import type { Run } from '../api/types'
import { RunPanel } from './RunPanel'
import { CheckBadge, ReviewBadge, StatusBadge } from './StatusBadge'

export function TaskView({ taskId }: { taskId: string }) {
  const detail = useQuery({ queryKey: keys.task(taskId), queryFn: () => api.task(taskId) })
  const [openRun, setOpenRun] = useState<string | null>(null)

  if (detail.isPending) return <Text>Loading…</Text>
  if (detail.isError) return <Text color="red.400">{detail.error.message}</Text>

  const { task, runs } = detail.data
  const active = runs.find((r) => r.id === openRun) ?? runs[0]

  return (
    <Stack spacing={4}>
      <Box>
        <Heading size="sm" mb={1}>
          Task
        </Heading>
        <Text whiteSpace="pre-wrap">{task.prompt}</Text>
        <Text fontSize="xs" color="gray.500" mt={1}>
          {task.repoPath} @ <Code fontSize="xs">{task.baseRef}</Code> ({task.baseSha.slice(0, 8)})
        </Text>
      </Box>

      <SimpleGrid columns={{ base: 1, lg: Math.min(runs.length, 4) }} spacing={3}>
        {runs.map((r) => (
          <RunCard key={r.id} run={r} active={r.id === active?.id} onClick={() => setOpenRun(r.id)} />
        ))}
      </SimpleGrid>

      {active && <RunPanel key={active.id} run={active} />}
    </Stack>
  )
}

function RunCard({ run, active, onClick }: { run: Run; active: boolean; onClick: () => void }) {
  return (
    <Box
      as="button"
      textAlign="left"
      p={3}
      borderWidth="1px"
      borderRadius="md"
      borderColor={active ? 'blue.400' : undefined}
      onClick={onClick}
    >
      <HStack justify="space-between">
        <Text fontWeight="semibold">Attempt {run.variant}</Text>
        <HStack spacing={1} flexWrap="wrap" justify="end">
          <ReviewBadge review={run.review} />
          <StatusBadge status={run.status} />
          {run.checkStatus && <CheckBadge status={run.checkStatus} />}
        </HStack>
      </HStack>
      <Text fontSize="xs" fontFamily="mono" color="gray.500" mt={1}>
        {run.branch}
      </Text>
      <HStack fontSize="xs" mt={2} spacing={3} color="gray.500">
        <Text>{run.numTurns ?? '–'} turns</Text>
        <Text>{run.costUsd !== undefined ? `$${run.costUsd.toFixed(4)}` : '–'}</Text>
        <Text>{duration(run)}</Text>
      </HStack>
      {run.resultSummary && (
        <Text fontSize="sm" mt={2} noOfLines={3}>
          {run.resultSummary}
        </Text>
      )}
    </Box>
  )
}

function duration(run: Run): string {
  if (!run.startedAt) return '–'
  const end = run.finishedAt ? Date.parse(run.finishedAt) : Date.now()
  const s = Math.round((end - Date.parse(run.startedAt)) / 1000)
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`
}
