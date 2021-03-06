package meteor

import cats.effect.{Concurrent, Timer}
import fs2.{Pipe, RaiseThrowable, Stream}
import meteor.api._
import meteor.codec.{Decoder, Encoder}
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model._

import scala.collection.immutable.Iterable
import scala.concurrent.duration._

class DefaultClient[F[_]: Concurrent: Timer: RaiseThrowable](
  jClient: DynamoDbAsyncClient
) extends Client[F]
    with DeleteOps
    with TableOps
    with GetOps
    with PutOps
    with ScanOps
    with UpdateOps
    with BatchWriteOps
    with BatchGetOps {

  def get[P: Encoder, U: Decoder](
    table: Table,
    partitionKey: P,
    consistentRead: Boolean
  ): F[Option[U]] =
    getOp[F, P, U](table, partitionKey, consistentRead)(jClient)

  def get[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    partitionKey: P,
    sortKey: S,
    consistentRead: Boolean
  ): F[Option[U]] =
    getOp[F, P, S, U](table, partitionKey, sortKey, consistentRead)(jClient)

  def retrieve[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    query: Query[P, S],
    consistentRead: Boolean,
    limit: Int
  ): fs2.Stream[F, U] =
    retrieveOp[F, P, S, U](table, query, consistentRead, limit)(jClient)

  def retrieve[P: Encoder, S: Encoder, U: Decoder](
    secondaryIndex: SecondaryIndex,
    query: Query[P, S],
    consistentRead: Boolean,
    limit: Int
  ): fs2.Stream[F, U] =
    retrieveOp[F, P, S, U](secondaryIndex, query, consistentRead, limit)(
      jClient
    )

  def retrieve[
    P: Encoder,
    U: Decoder
  ](
    table: Table,
    partitionKey: P,
    consistentRead: Boolean,
    limit: Int
  ): fs2.Stream[F, U] =
    retrieveOp[F, P, U](table, partitionKey, consistentRead, limit)(jClient)

  def retrieve[
    P: Encoder,
    U: Decoder
  ](
    secondaryIndex: SecondaryIndex,
    partitionKey: P,
    consistentRead: Boolean,
    limit: Int
  ): fs2.Stream[F, U] =
    retrieveOp[F, P, U](secondaryIndex, partitionKey, consistentRead, limit)(
      jClient
    )

  def put[T: Encoder](
    tableName: String,
    t: T
  ): F[Unit] = putOp[F, T](tableName, t)(jClient)

  def put[T: Encoder](
    tableName: String,
    t: T,
    condition: Expression
  ): F[Unit] = putOp[F, T](tableName, t, condition)(jClient)

  def put[T: Encoder, U: Decoder](
    tableName: String,
    t: T
  ): F[Option[U]] = putOp[F, T, U](tableName, t)(jClient)

  def put[T: Encoder, U: Decoder](
    tableName: String,
    t: T,
    condition: Expression
  ): F[Option[U]] = putOp[F, T, U](tableName, t, condition)(jClient)

  def delete[P: Encoder, S: Encoder](
    table: Table,
    partitionKey: P,
    sortKey: S
  ): F[Unit] = deleteOp[F, P, S](table, partitionKey, sortKey)(jClient)

  def delete[P: Encoder](
    table: Table,
    partitionKey: P
  ): F[Unit] = deleteOp[F, P](table, partitionKey)(jClient)

  def scan[T: Decoder](
    tableName: String,
    filter: Expression,
    consistentRead: Boolean,
    parallelism: Int
  ): fs2.Stream[F, T] =
    scanOp[F, T](tableName, filter, consistentRead, parallelism)(jClient)

  def scan[T: Decoder](
    tableName: String,
    consistentRead: Boolean,
    parallelism: Int
  ): fs2.Stream[F, T] =
    scanOp[F, T](tableName, consistentRead, parallelism)(jClient)

  def update[P: Encoder, U: Decoder](
    table: Table,
    partitionKey: P,
    update: Expression,
    returnValue: ReturnValue
  ): F[Option[U]] =
    updateOp[F, P, U](table, partitionKey, update, returnValue)(jClient)

  def update[P: Encoder, U: Decoder](
    table: Table,
    partitionKey: P,
    update: Expression,
    condition: Expression,
    returnValue: ReturnValue
  ): F[Option[U]] =
    updateOp[F, P, U](table, partitionKey, update, condition, returnValue)(
      jClient
    )

  def update[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    partitionKey: P,
    sortKey: S,
    update: Expression,
    returnValue: ReturnValue
  ): F[Option[U]] =
    updateOp[F, P, S, U](table, partitionKey, sortKey, update, returnValue)(
      jClient
    )

  def update[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    partitionKey: P,
    sortKey: S,
    update: Expression,
    condition: Expression,
    returnValue: ReturnValue
  ): F[Option[U]] =
    updateOp[F, P, S, U](
      table,
      partitionKey,
      sortKey,
      update,
      condition,
      returnValue
    )(
      jClient
    )

  def update[P: Encoder](
    table: Table,
    partitionKey: P,
    update: Expression
  ): F[Unit] =
    updateOp[F, P](table, partitionKey, update)(jClient)

  def update[P: Encoder](
    table: Table,
    partitionKey: P,
    update: Expression,
    condition: Expression
  ): F[Unit] =
    updateOp[F, P](table, partitionKey, update, condition)(jClient)

  def update[P: Encoder, S: Encoder](
    table: Table,
    partitionKey: P,
    sortKey: S,
    update: Expression
  ): F[Unit] =
    updateOp[F, P, S](table, partitionKey, sortKey, update)(
      jClient
    )

  def update[P: Encoder, S: Encoder](
    table: Table,
    partitionKey: P,
    sortKey: S,
    update: Expression,
    condition: Expression
  ): F[Unit] =
    updateOp[F, P, S](table, partitionKey, sortKey, update, condition)(
      jClient
    )

  def batchGet(
    requests: Map[String, BatchGet],
    backoffStrategy: BackoffStrategy
  ): F[Map[String, Iterable[AttributeValue]]] =
    batchGetOp[F](requests, backoffStrategy)(jClient)

  def batchGet[P: Encoder, U: Decoder](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    keys: Iterable[P],
    backoffStrategy: BackoffStrategy
  ): F[Iterable[U]] =
    batchGetOp[F, P, U](
      table,
      consistentRead,
      projection,
      keys,
      backoffStrategy
    )(jClient)

  def batchGet[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    keys: Iterable[(P, S)],
    backoffStrategy: BackoffStrategy
  ): F[Iterable[U]] =
    batchGetOp[F, P, S, U](
      table,
      consistentRead,
      projection,
      keys,
      backoffStrategy
    )(jClient)

  def batchGet[P: Encoder, U: Decoder](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, P, U] =
    batchGetOp[F, P, U](
      table,
      consistentRead,
      projection,
      maxBatchWait,
      parallelism,
      backoffStrategy
    )(jClient)

  def batchGet[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    consistentRead: Boolean,
    projection: Expression,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, (P, S), U] =
    batchGetOp[F, P, S, U](
      table,
      consistentRead,
      projection,
      maxBatchWait,
      parallelism,
      backoffStrategy
    )(jClient)

  def batchGet[P: Encoder, U: Decoder](
    table: Table,
    consistentRead: Boolean,
    keys: Iterable[P],
    backoffStrategy: BackoffStrategy
  ): F[Iterable[U]] =
    batchGetOp[F, P, U](
      table,
      consistentRead,
      Expression.empty,
      keys,
      backoffStrategy
    )(
      jClient
    )

  def batchGet[P: Encoder, S: Encoder, U: Decoder](
    table: Table,
    consistentRead: Boolean,
    keys: Iterable[(P, S)],
    backoffStrategy: BackoffStrategy
  ): F[Iterable[U]] =
    batchGetOp[F, P, S, U](
      table,
      consistentRead,
      Expression.empty,
      keys,
      backoffStrategy
    )(
      jClient
    )

  def batchWrite[P: Encoder, I: Encoder](
    table: Table,
    maxBatchWait: FiniteDuration,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, Either[P, I], Unit] =
    batchWriteInorderedOp[F, P, I](table, maxBatchWait, backoffStrategy)(
      jClient
    )

  def batchWrite[P: Encoder, S: Encoder, I: Encoder](
    table: Table,
    maxBatchWait: FiniteDuration,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, Either[(P, S), I], Unit] =
    batchWriteInorderedOp[F, P, S, I](table, maxBatchWait, backoffStrategy)(
      jClient
    )

  def batchPut[T: Encoder](
    table: Table,
    maxBatchWait: FiniteDuration,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, T, Unit] =
    batchPutInorderedOp[F, T](table, maxBatchWait, backoffStrategy)(jClient)

  def batchPut[T: Encoder](
    table: Table,
    items: Iterable[T],
    backoffStrategy: BackoffStrategy
  ): F[Unit] = {
    val itemsStream = Stream.iterable(items).covary[F]
    val pipe =
      batchPutInorderedOp[F, T](table, Int.MaxValue.seconds, backoffStrategy)(
        jClient
      )
    pipe.apply(itemsStream).compile.drain
  }

  def batchPutUnordered[T: Encoder](
    table: Table,
    items: Set[T],
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  ): F[Unit] = {
    val itemsStream = Stream.iterable(items).covary[F]
    val pipe =
      batchPutUnorderedOp[F, T](
        table.name,
        maxBatchWait,
        parallelism,
        backoffStrategy
      )(jClient)
    pipe.apply(itemsStream).compile.drain
  }

  def batchDelete[P: Encoder](
    table: Table,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, P, Unit] =
    _.through(
      batchDeleteUnorderedOp[F, P](
        table,
        maxBatchWait,
        parallelism,
        backoffStrategy
      )(
        jClient
      )
    )

  def batchDelete[P: Encoder, S: Encoder](
    table: Table,
    maxBatchWait: FiniteDuration,
    parallelism: Int,
    backoffStrategy: BackoffStrategy
  ): Pipe[F, (P, S), Unit] =
    _.through(
      batchDeleteUnorderedOp[F, P, S](
        table,
        maxBatchWait,
        parallelism,
        backoffStrategy
      )(
        jClient
      )
    )

  def describe(tableName: String): F[TableDescription] =
    describeOp[F](tableName)(jClient)

  def createTable(
    table: Table,
    attributeDefinition: Map[String, DynamoDbType],
    globalSecondaryIndexes: Set[GlobalSecondaryIndex],
    localSecondaryIndexes: Set[LocalSecondaryIndex],
    billingMode: BillingMode
  ): F[Unit] =
    createTableOp[F](
      table,
      attributeDefinition,
      globalSecondaryIndexes,
      localSecondaryIndexes,
      billingMode,
      waitTillReady = true
    )(jClient)

  def deleteTable(tableName: String): F[Unit] =
    deleteTableOp[F](tableName)(jClient)
}
