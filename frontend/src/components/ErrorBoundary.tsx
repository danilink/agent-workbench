import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Box, Button, Code, Heading, Text } from '@chakra-ui/react'

interface State {
  error: Error | null
}

/** Without this, any render error unmounts the whole tree and leaves a blank page with no clue why. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('UI crashed', error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <Box p={6} maxW="3xl" role="alert">
        <Heading size="md" mb={2}>
          Something broke in the UI
        </Heading>
        <Text mb={4} color="gray.500">
          Runs keep going on the server; reloading is safe.
        </Text>
        <Code display="block" whiteSpace="pre-wrap" p={3} mb={4} fontSize="xs">
          {error.stack ?? error.message}
        </Code>
        <Button size="sm" onClick={() => window.location.reload()}>
          Reload
        </Button>
      </Box>
    )
  }
}
