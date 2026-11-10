import { useState, type FormEvent } from 'react'
import {
  Button,
  FormControl,
  FormHelperText,
  FormLabel,
  Input,
  NumberDecrementStepper,
  NumberIncrementStepper,
  NumberInput,
  NumberInputField,
  NumberInputStepper,
  Select,
  Stack,
  Text,
  Textarea,
} from '@chakra-ui/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'

import { api, keys } from '../api/client'
import type { WorkbenchConfig } from '../api/types'

interface Props {
  config: WorkbenchConfig
  onCreated: (taskId: string) => void
}

export function NewTaskForm({ config, onCreated }: Props) {
  const qc = useQueryClient()
  const [repoPath, setRepoPath] = useState(config.repos[0] ?? '')
  const [baseRef, setBaseRef] = useState('HEAD')
  const [prompt, setPrompt] = useState('')
  const [variants, setVariants] = useState(1)

  const create = useMutation({
    mutationFn: api.createTask,
    onSuccess: (detail) => {
      qc.setQueryData(keys.task(detail.task.id), detail)
      void qc.invalidateQueries({ queryKey: keys.tasks })
      setPrompt('')
      onCreated(detail.task.id)
    },
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate({ repoPath, baseRef, prompt, variants })
  }

  if (config.repos.length === 0) {
    return (
      <Text fontSize="sm" color="orange.400">
        No repositories are allow-listed. Set <code>WORKBENCH_REPOS=/abs/path/to/repo</code> and restart Play.
      </Text>
    )
  }

  return (
    <form onSubmit={submit}>
      <Stack spacing={3}>
        <FormControl>
          <FormLabel fontSize="sm">Repository</FormLabel>
          <Select size="sm" value={repoPath} onChange={(e) => setRepoPath(e.target.value)}>
            {config.repos.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </Select>
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Base ref</FormLabel>
          <Input size="sm" fontFamily="mono" value={baseRef} onChange={(e) => setBaseRef(e.target.value)} />
        </FormControl>
        <FormControl isRequired>
          <FormLabel fontSize="sm">Prompt</FormLabel>
          <Textarea
            size="sm"
            rows={5}
            placeholder="Add a GET /api/version endpoint that returns the git sha…"
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
          />
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Parallel attempts</FormLabel>
          <NumberInput
            size="sm"
            min={1}
            max={config.maxVariants}
            value={variants}
            onChange={(_, n) => setVariants(Number.isNaN(n) ? 1 : n)}
          >
            <NumberInputField />
            <NumberInputStepper>
              <NumberIncrementStepper />
              <NumberDecrementStepper />
            </NumberInputStepper>
          </NumberInput>
          <FormHelperText>
            Each attempt is its own Claude Code session in its own worktree. Max {config.maxParallelRuns} run at
            once.
          </FormHelperText>
        </FormControl>
        {create.isError && (
          <Text fontSize="sm" color="red.400">
            {create.error.message}
          </Text>
        )}
        <Button type="submit" size="sm" colorScheme="blue" isLoading={create.isPending} isDisabled={!prompt.trim()}>
          Dispatch
        </Button>
      </Stack>
    </form>
  )
}
