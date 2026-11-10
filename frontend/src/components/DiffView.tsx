import { Box, Text } from '@chakra-ui/react'

/** Minimal unified-diff renderer. Good enough for review; swap for a real diff viewer later. */
export function DiffView({ diff }: { diff: string }) {
  if (!diff.trim()) return <Text fontSize="sm" color="gray.500">No changes.</Text>
  return (
    <Box as="pre" fontFamily="mono" fontSize="xs" overflowX="auto" maxH="60vh" overflowY="auto" borderWidth="1px" borderRadius="md">
      {diff.split('\n').map((line, i) => (
        <Box as="span" display="block" px={2} key={i} {...style(line)}>
          {line || ' '}
        </Box>
      ))}
    </Box>
  )
}

function style(line: string) {
  if (line.startsWith('diff --git')) return { fontWeight: 'bold', bg: 'blackAlpha.100', _dark: { bg: 'whiteAlpha.100' }, mt: 2 }
  if (line.startsWith('@@')) return { color: 'purple.500' }
  if (line.startsWith('+') && !line.startsWith('+++')) return { bg: 'green.50', _dark: { bg: 'green.900' } }
  if (line.startsWith('-') && !line.startsWith('---')) return { bg: 'red.50', _dark: { bg: 'red.900' } }
  return {}
}
