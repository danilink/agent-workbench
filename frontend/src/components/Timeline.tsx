import { useEffect, useRef } from 'react'
import { Box, Code, HStack, Stack, Tag, Text } from '@chakra-ui/react'

import type { RunEvent } from '../api/types'

/** Renders the normalised agent events. Tool results are folded under their tool call. */
export function Timeline({ events }: { events: RunEvent[] }) {
  const bottom = useRef<HTMLDivElement>(null)
  // Braces matter: newer Chrome returns a Promise from scrollIntoView, and React would call it as the cleanup.
  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'nearest' })
  }, [events.length])

  const results = new Map<string, Extract<RunEvent, { kind: 'ToolResult' }>>()
  for (const e of events) if (e.kind === 'ToolResult') results.set(e.payload.toolUseId, e)

  if (events.length === 0) return <Text fontSize="sm" color="gray.500">No events yet.</Text>

  return (
    <Stack spacing={2} maxH="60vh" overflowY="auto" fontSize="sm">
      {events.map((e) => {
        switch (e.kind) {
          case 'Init':
            return <Line key={e.seq} tag="init" color="purple">session {e.payload.sessionId} · {e.payload.model ?? '?'}</Line>
          case 'Text':
            return <Line key={e.seq} tag="claude" color="blue"><Text whiteSpace="pre-wrap">{e.payload.text}</Text></Line>
          case 'ToolUse': {
            const res = results.get(e.payload.id)
            return (
              <Line key={e.seq} tag={e.payload.name} color={res?.payload.isError ? 'red' : 'teal'}>
                <Code fontSize="xs" whiteSpace="pre-wrap" display="block">{summariseInput(e.payload.input)}</Code>
                {res && (
                  <Box as="details" mt={1}>
                    <Box as="summary" cursor="pointer" color="gray.500" fontSize="xs">result</Box>
                    <Code fontSize="xs" whiteSpace="pre-wrap" display="block">{res.payload.preview}</Code>
                  </Box>
                )}
              </Line>
            )
          }
          case 'ToolResult':
            return null
          case 'Result':
            return (
              <Line key={e.seq} tag="result" color={e.payload.isError ? 'red' : 'green'}>
                <Text whiteSpace="pre-wrap">{e.payload.summary}</Text>
              </Line>
            )
          case 'Log':
            return <Line key={e.seq} tag={e.payload.stream} color="gray"><Code fontSize="xs">{e.payload.line}</Code></Line>
          case 'Unknown':
            return null
        }
      })}
      <div ref={bottom} />
    </Stack>
  )
}

function Line({ tag, color, children }: { tag: string; color: string; children: React.ReactNode }) {
  return (
    <HStack align="start" spacing={2}>
      <Tag size="sm" colorScheme={color} flexShrink={0} minW="72px" justifyContent="center">
        {tag}
      </Tag>
      <Box minW={0} flex={1}>{children}</Box>
    </HStack>
  )
}

function summariseInput(input: unknown): string {
  if (input && typeof input === 'object') {
    const o = input as Record<string, unknown>
    for (const k of ['command', 'file_path', 'pattern', 'path', 'url']) if (typeof o[k] === 'string') return o[k] as string
  }
  const s = JSON.stringify(input)
  return s.length > 300 ? `${s.slice(0, 300)}…` : s
}
