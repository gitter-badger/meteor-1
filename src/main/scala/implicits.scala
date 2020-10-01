package meteor

import java.util.concurrent.CompletableFuture

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import meteor.codec.{Decoder, DecoderFailure}
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

object implicits {
  implicit class FromCompletableFuture[A](thunk: () => CompletableFuture[A]) {
    def liftF[F[_]: Concurrent]: F[A] =
      Concurrent[F].cancelable[A] { cb =>

        val future = thunk()
        future.whenComplete { (ok, err) =>
          cb(Option(err).toLeft(ok))
        }

        Sync[F].delay(future.cancel(true)).void
      }
  }

  implicit class ToAttributeValue(m: java.util.Map[String, AttributeValue]) {
    def attemptDecode[T: Decoder]: Either[DecoderFailure, Option[T]] = {
      Option(m)
        .filter(_.size > 0)
        .map(xs => AttributeValue.builder().m(xs).build())
        .traverse(Decoder[T].read)
    }
  }
}
