import { Box, HStack, Stack, Text } from '@chakra-ui/react'
import { useQuery } from '@tanstack/react-query'

import { api, keys } from '../api/client'
import { StatusBadge } from './StatusBadge'

interface Props {
  selected: string | null
  onSelect: (taskId: string) => void
}

export function TaskList({ selected, onSelect }: Props) {
  const tasks = useQuery({ queryKey: keys.tasks, queryFn: api.tasks })

  if (tasks.isPending) return <Text fontSize="sm">Loading…</Text>
  if (tasks.isError) return <Text fontSize="sm" color="red.400">{tasks.error.message}</Text>
  if (tasks.data.length === 0) return <Text fontSize="sm" color="gray.500">No tasks yet.</Text>

  return (
    <Stack spacing={2}>
      {tasks.data.map(({ task, runs }) => (
        <Box
          key={task.id}
          as="button"
          textAlign="left"
          p={2}
          borderWidth="1px"
          borderRadius="md"
          borderColor={selected === task.id ? 'blue.400' : undefined}
          onClick={() => onSelect(task.id)}
        >
          <Text fontSize="sm" noOfLines={2}>
            {task.prompt}
          </Text>
          <HStack mt={1} spacing={1} flexWrap="wrap">
            {runs.map((r) => (
              <StatusBadge key={r.id} status={r.status} />
            ))}
          </HStack>
        </Box>
      ))}
    </Stack>
  )
}
