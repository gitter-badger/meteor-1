package meteor
package api

import java.util.{Map => jMap}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.{Pipe, _}
import meteor.codec.{Decoder, Encoder}
import meteor.implicits._
import software.amazon.awssdk.core.retry.RetryPolicyContext
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  BatchGetItemRequest,
  BatchGetItemResponse,
  KeysAndAttributes
}

import scala.collection.immutable.Iterable
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.compat.java8.DurationConverters._

case class BatchGet(
  consistentRead: Boolean,
  projection: Expression,
  values: Iterable[AttributeValue]
)

trait BatchGetOps extends DedupOps {

  // 100 is the maximum amount of items for BatchGetItem
  val MaxBatchGetSize = 100

  def batchGetOp[F[_]: Timer: Concurrent: RaiseThrowable](
    requests: Map[String, BatchGet],
    backoffStrategy: BackoffStrategy
  )(jClient: DynamoDbAsyncClient): F[Map[String, Iterable[AttributeValue]]] = {
    val responses = requests.map {
      case (tableName, get) =>
        Stream.iterable(get.values).covary[F].chunkN(MaxBatchGetSize).map {
          chunk =>
            // remove potential duplicated keys
            val keys =
              dedupInOrdered(chunk)(identity)(_.m())
            val keyAndAttrs =
              mkRequest(keys, get.consistentRead, get.projection)
            val req = Map(tableName -> keyAndAttrs).asJava
            loop[F](req, backoffStrategy)(jClient)
        }.parJoinUnbounded
    }
    Stream.iterable(responses).covary[F].flatten.compile.toList.map { resps =>
      resps.foldLeft(Map.empty[String, List[AttributeValue]]) { (acc, elem) =>
        acc ++ {
          elem.responses().asScala.map {
            case (tableName, avs) =>
              tableName -> avs.asScala.toList.map(av =>
                AttributeValue.builder().m(av).build())
          }
        }
      }
    }
  }

  def batchGetOp[
    F[_]: Timer: Concurrent: RaiseThrowable,
    P: Encoder,
    T: Decoder
  ](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  )(jClient: DynamoDbAsyncClient): Pipe[F, P, T] =
    batchGetOpInternal[F, P, T](
      table.name,
      consistentRead,
      projection,
      maxBatchWait,
      parallelism,
      jClient,
      backoffStrategy
    ) { key =>
      table.keys(key, None).asAttributeValue
    }

  def batchGetOp[
    F[_]: Timer: Concurrent: RaiseThrowable,
    P: Encoder,
    S: Encoder,
    T: Decoder
  ](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  )(jClient: DynamoDbAsyncClient): Pipe[F, (P, S), T] = {
    in =>
      val pipe = batchGetOpInternal[F, AttributeValue, T](
        table.name,
        consistentRead,
        projection,
        maxBatchWait,
        parallelism,
        jClient,
        backoffStrategy
      )(identity)
      in.map {
        case (p, s) =>
          table.keys(p, s.some).asAttributeValue
      }.through(pipe)
  }

  def batchGetOp[F[_]: Concurrent: Timer, P: Encoder, S: Encoder, T: Decoder](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    keys: Iterable[(P, S)],
    backoffStrategy: BackoffStrategy
  )(jClient: DynamoDbAsyncClient): F[Iterable[T]] = {
    val attrKeys = keys.map {
      case (p, s) =>
        table.keys(p, s.some).asAttributeValue
    }
    batchGetOpInternal[F, AttributeValue, T](
      table.name,
      consistentRead,
      projection,
      attrKeys,
      jClient,
      backoffStrategy
    )(identity)
  }

  def batchGetOp[F[_]: Concurrent: Timer, P: Encoder, T: Decoder](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    keys: Iterable[P],
    backoffStrategy: BackoffStrategy
  )(jClient: DynamoDbAsyncClient): F[Iterable[T]] =
    batchGetOpInternal[F, P, T](
      table.name,
      consistentRead,
      projection,
      keys,
      jClient,
      backoffStrategy
    ) { key =>
      table.keys(key, None).asAttributeValue
    }

  private def batchGetOpInternal[
    F[_]: Concurrent: Timer,
    K: Encoder,
    T: Decoder
  ](
    tableName: String,
    consistentRead: Boolean,
    projection: Expression,
    keys: Iterable[K],
    jClient: DynamoDbAsyncClient,
    backoffStrategy: BackoffStrategy
  )(mkKey: K => AttributeValue): F[Iterable[T]] = {
    Stream.iterable(keys).chunkN(MaxBatchGetSize).flatMap { chunk =>
      val keys = dedupInOrdered(chunk)(mkKey)(t => mkKey(t).m())
      val keyAndAttrs = if (projection.isEmpty) {
        KeysAndAttributes.builder().consistentRead(
          consistentRead
        ).keys(keys: _*).build()
      } else {
        mkRequest(keys, consistentRead, projection)
      }
      val req = Map(tableName -> keyAndAttrs).asJava
      loop[F](req, backoffStrategy)(jClient)
    }.flatMap(parseResponse[F, T](tableName)).compile.to(Iterable)
  }

  private def batchGetOpInternal[
    F[_]: Timer: Concurrent: RaiseThrowable,
    K: Encoder,
    T: Decoder
  ](
    tableName: String,
    consistentRead: Boolean,
    projection: Expression,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    jClient: DynamoDbAsyncClient,
    backoffStrategy: BackoffStrategy
  )(mkKey: K => AttributeValue): Pipe[F, K, T] =
    in => {
      val responses = in.groupWithin(MaxBatchGetSize, maxBatchWait).map {
        chunk =>
          // remove potential duplicated keys
          val keys =
            dedupInOrdered(chunk)(mkKey)(t => mkKey(t).m())
          val keyAndAttrs = mkRequest(keys, consistentRead, projection)
          val req = Map(tableName -> keyAndAttrs).asJava

          loop[F](req, backoffStrategy)(jClient)
      }
      responses.parJoin(parallelism).flatMap(parseResponse[F, T](tableName))
    }

  private[api] def mkRequest(
    keys: Seq[jMap[String, AttributeValue]],
    consistentRead: Boolean,
    projection: Expression
  ): KeysAndAttributes = {
    val bd = KeysAndAttributes.builder().consistentRead(
      consistentRead
    ).keys(keys: _*)
    if (projection.nonEmpty) {
      bd.projectionExpression(
        projection.expression
      )
      if (projection.attributeNames.isEmpty) {
        bd.build()
      } else {
        bd.expressionAttributeNames(
          projection.attributeNames.asJava
        ).build()
      }
    } else {
      bd.build()
    }
  }

  private[api] def parseResponse[F[_]: RaiseThrowable, U: Decoder](
    tableName: String
  )(
    resp: BatchGetItemResponse
  ): Stream[F, U] = {
    Stream.emits(resp.responses().get(tableName).asScala).covary[F].flatMap {
      av =>
        Stream.fromEither(Decoder[U].read(av)).covary[F]
    }
  }

  private[api] def loop[F[_]: Concurrent: Timer](
    items: jMap[
      String,
      KeysAndAttributes
    ],
    backoffStrategy: BackoffStrategy,
    retried: Int = 0
  )(
    jClient: DynamoDbAsyncClient
  ): Stream[F, BatchGetItemResponse] = {
    val req = BatchGetItemRequest.builder().requestItems(items).build()
    Stream.eval((() => jClient.batchGetItem(req)).liftF[F]).flatMap {
      resp =>
        Stream.emit(resp) ++ {
          val hasNext =
            resp.hasUnprocessedKeys && !resp.unprocessedKeys().isEmpty
          if (hasNext) {
            val nextDelay = backoffStrategy.computeDelayBeforeNextRetry(
              RetryPolicyContext.builder().retriesAttempted(retried).build()
            ).toScala
            Stream.sleep(nextDelay) >> loop[F](
              resp.unprocessedKeys(),
              backoffStrategy,
              retried + 1
            )(
              jClient
            )
          } else {
            Stream.empty
          }
        }
    }
  }
}

object BatchGetOps extends BatchGetOps
