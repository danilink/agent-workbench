package workbench.domain

import java.util.UUID
import scala.util.Try

opaque type TaskId = UUID

object TaskId:
  def apply(value: UUID): TaskId = value
  def random(): TaskId = UUID.randomUUID()
  def parse(raw: String): Option[TaskId] = Try(UUID.fromString(raw)).toOption
  extension (id: TaskId) def value: UUID = id
