package workbench.events

import javax.inject.{Inject, Singleton}

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}

import workbench.domain.{Run, RunEvent}

enum BusMessage:
  case RunUpdated(run: Run)
  case EventAppended(event: RunEvent)

/** In-process fan-out of run updates to every SSE subscriber.
  *
  * Single-node by design (see docs/adr/0003). Clients never rely on the bus for completeness: they load
  * history over REST and use the stream only for what happens next, so a dropped message costs freshness, not
  * data.
  */
@Singleton
final class EventBus @Inject() (mat: Materializer):
  private given Materializer = mat

  private val (queue, hub) =
    Source
      .queue[BusMessage](bufferSize = 1024, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink[BusMessage](bufferSize = 256))(Keep.both)
      .run()

  // Keep the hub draining when nobody is subscribed, otherwise it backpressures the queue.
  hub.runWith(Sink.ignore)

  def publish(m: BusMessage): Unit = queue.offer(m): Unit

  def subscribe: Source[BusMessage, NotUsed] = hub
