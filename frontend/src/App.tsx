import { useState } from 'react'
import { Badge, Box, Divider, Flex, Heading, HStack, Text } from '@chakra-ui/react'
import { useQuery } from '@tanstack/react-query'

import { api, keys } from './api/client'
import { useWorkbenchStream } from './api/stream'
import { NewTaskForm } from './components/NewTaskForm'
import { TaskList } from './components/TaskList'
import { TaskView } from './components/TaskView'

export function App() {
  const stream = useWorkbenchStream()
  const config = useQuery({ queryKey: keys.config, queryFn: api.config })
  const [selected, setSelected] = useState<string | null>(null)

  return (
    <Flex h="100vh" direction={{ base: 'column', md: 'row' }}>
      <Box
        as="aside"
        w={{ base: 'full', md: '360px' }}
        flexShrink={0}
        borderRightWidth={{ md: '1px' }}
        p={4}
        overflowY="auto"
      >
        <HStack justify="space-between" mb={4}>
          <Heading size="md">Agent Workbench</Heading>
          <HStack spacing={2}>
            {config.data && <Badge>{config.data.runner}</Badge>}
            <Badge colorScheme={stream === 'open' ? 'green' : 'orange'}>{stream}</Badge>
          </HStack>
        </HStack>
        {config.data && <NewTaskForm config={config.data} onCreated={setSelected} />}
        {config.isError && <Text color="red.400">Backend not reachable on /api. Is Play running on :9000?</Text>}
        <Divider my={4} />
        <TaskList selected={selected} onSelect={setSelected} />
      </Box>
      <Box as="main" flex={1} p={4} overflowY="auto" minW={0}>
        {selected ? (
          <TaskView taskId={selected} />
        ) : (
          <Text color="gray.500">Create a task or pick one to watch its agent sessions.</Text>
        )}
      </Box>
    </Flex>
  )
}
