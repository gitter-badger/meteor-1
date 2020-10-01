package meteor

import cats._
import cats.implicits._
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync, Timer}
import meteor.codec.Encoder

import scala.concurrent.duration.FiniteDuration

object Util {
  def retryOf[F[_]: Timer: Sync, T](
    f: F[T],
    interval: FiniteDuration,
    maxRetry: Int
  )(cond: T => Boolean): F[T] = {
    def ref = Ref.of[F, Int](0)

    for {
      r <- ref
      t <- f
    } yield {
      if (cond(t)) {
        t.pure[F]
      } else {
        r.get.flatMap {
          case i if i < maxRetry =>
            Timer[F].sleep(interval) >> r.set(i + 1) >> f
          case _ =>
            new Exception("Max retry reached").raiseError[F, T]
        }
      }
    }
  }.flatten

  def dataResource[
    F[_]: Monad,
    G[_]: Traverse,
    T: Encoder,
    P: Encoder,
    S: Encoder
  ](
    gt: G[T],
    partitionKey: T => P,
    sortKey: T => S,
    tableName: TableName,
    client: Client[F]
  ): Resource[F, G[T]] =
    Resource.make {
      gt.traverse(t => client.put(t, tableName).as(t))
    } { gt =>
      gt.traverse(
        t => client.delete(partitionKey(t), sortKey(t), tableName)
      ).map(_.combineAll)
    }
}
