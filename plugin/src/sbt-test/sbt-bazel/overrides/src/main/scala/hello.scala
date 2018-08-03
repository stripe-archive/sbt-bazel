package hello

import io.circe.Json

object Hello {
  val hello: Json = Json.fromString("hello")
}
