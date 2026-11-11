import { Box, Button, HStack, Tab, TabList, TabPanel, TabPanels, Tabs, Text } from '@chakra-ui/react'
import { useMutation, useQuery } from '@tanstack/react-query'

import { api, keys } from '../api/client'
import { TERMINAL, type Run } from '../api/types'
import { DiffView } from './DiffView'
import { ReviewBar } from './ReviewBar'
import { Timeline } from './Timeline'

export function RunPanel({ run }: { run: Run }) {
  const events = useQuery({ queryKey: keys.events(run.id), queryFn: () => api.events(run.id) })
  const diffAvailable = run.review === 'Pending' && run.status !== 'Queued' && run.status !== 'Preparing'
  const diff = useQuery({ queryKey: keys.diff(run.id), queryFn: () => api.diff(run.id), enabled: diffAvailable })
  const cancel = useMutation({ mutationFn: () => api.cancel(run.id) })
  const finished = TERMINAL.has(run.status)

  return (
    <Box borderWidth="1px" borderRadius="md" p={3}>
      <HStack justify="space-between" mb={2} flexWrap="wrap" gap={2}>
        <Text fontSize="sm" color="gray.500">
          Attempt {run.variant} · <code>{run.worktreePath}</code>
        </Text>
        {!finished && (
          <Button size="xs" colorScheme="orange" variant="outline" onClick={() => cancel.mutate()} isLoading={cancel.isPending}>
            Cancel
          </Button>
        )}
        {finished && run.review === 'Pending' && <ReviewBar runId={run.id} />}
      </HStack>
      {run.error && (
        <Text fontSize="sm" color="red.400" mb={2}>
          {run.error}
        </Text>
      )}
      <Tabs size="sm" isLazy>
        <TabList>
          <Tab>Timeline ({events.data?.length ?? 0})</Tab>
          <Tab isDisabled={!diffAvailable}>Diff</Tab>
          <Tab isDisabled={!run.checkStatus}>Check</Tab>
        </TabList>
        <TabPanels>
          <TabPanel px={0}>
            {events.isError ? <Text color="red.400">{events.error.message}</Text> : <Timeline events={events.data ?? []} />}
          </TabPanel>
          <TabPanel px={0}>
            {diff.isPending ? (
              <Text fontSize="sm">Loading diff…</Text>
            ) : diff.isError ? (
              <Text color="red.400">{diff.error.message}</Text>
            ) : (
              <>
                <Button size="xs" mb={2} onClick={() => void diff.refetch()} isLoading={diff.isFetching}>
                  Refresh
                </Button>
                <DiffView diff={diff.data} />
              </>
            )}
          </TabPanel>
          <TabPanel px={0}>
            {run.checkStatus === 'Running' ? (
              <Text fontSize="sm">Running check…</Text>
            ) : (
              <Box
                as="pre"
                fontSize="xs"
                fontFamily="mono"
                maxH="400px"
                overflow="auto"
                whiteSpace="pre-wrap"
                p={2}
                borderWidth="1px"
                borderRadius="md"
              >
                {run.checkOutput ?? 'No check output.'}
              </Box>
            )}
          </TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  )
}
