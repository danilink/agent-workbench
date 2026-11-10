package workbench.domain

import java.util.UUID
import scala.util.Try

opaque type RunId = UUID

object RunId:
  def apply(value: UUID): RunId          = value
  def random(): RunId                    = UUID.randomUUID()
  def parse(raw: String): Option[RunId]  = Try(UUID.fromString(raw)).toOption
  extension (id: RunId) def value: UUID  = id
