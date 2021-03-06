package meteor

import cats._
import cats.implicits._
import meteor.codec.Encoder
import meteor.syntax._
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  ScalarAttributeType
}

import java.util
import scala.jdk.CollectionConverters._

case class Key(
  name: String,
  attributeType: DynamoDbType
)

sealed trait Index

case class Table(
  name: String,
  partitionKey: Key,
  sortKey: Option[Key]
) extends Index {
  def keys[P: Encoder, S: Encoder](
    partitionKeyValue: P,
    sortKeyValue: Option[S]
  ): util.Map[String, AttributeValue] = {
    val partitionK =
      Map(partitionKey.name -> partitionKeyValue.asAttributeValue)

    val optSortK = for {
      key <- sortKey
      value <- sortKeyValue
    } yield Map(key.name -> value.asAttributeValue)

    optSortK.fold(partitionK) { sortK =>
      partitionK ++ sortK
    }.asJava
  }
}

case class SecondaryIndex(
  tableName: String,
  indexName: String,
  partitionKey: Key,
  sortKey: Option[Key]
) extends Index

sealed trait SortKeyQuery[T]
object SortKeyQuery {
  case class Empty[T]() extends SortKeyQuery[T]
  case class EqualTo[T](value: T) extends SortKeyQuery[T]
  case class LessThan[T](value: T) extends SortKeyQuery[T]
  case class LessOrEqualTo[T](value: T) extends SortKeyQuery[T]
  case class GreaterThan[T](value: T) extends SortKeyQuery[T]
  case class GreaterOrEqualTo[T](value: T) extends SortKeyQuery[T]
  case class Between[T: Ordering](from: T, to: T) extends SortKeyQuery[T]
  case class BeginsWith[T](value: T) extends SortKeyQuery[T]
}

case class Expression(
  expression: String,
  attributeNames: Map[String, String],
  attributeValues: Map[String, AttributeValue]
) {
  val isEmpty: Boolean = this.expression.isEmpty
  val nonEmpty: Boolean = !isEmpty
}

object Expression {
  val empty: Expression =
    Expression("", Map.empty[String, String], Map.empty[String, AttributeValue])

  def apply(expression: String): Expression =
    Expression(expression, Map.empty, Map.empty)

  implicit val monoidOfExpression: Monoid[Expression] = Monoid.instance(
    Expression.empty,
    { (left, right) =>
      if (left.isEmpty) {
        right
      } else if (right.isEmpty) {
        left
      } else {
        Expression(
          left.expression + " AND " + right.expression,
          left.attributeNames ++ right.attributeNames,
          left.attributeValues ++ right.attributeValues
        )
      }
    }
  )
}

case class Query[P: Encoder, S: Encoder](
  partitionKey: P,
  sortKeyQuery: SortKeyQuery[S],
  filter: Expression
) {
  def keyCondition(index: Index): Expression = {
    val (partitionKeySchema, optSortKeySchema) = index match {
      case Table(_, pk, sk)             => (pk, sk)
      case SecondaryIndex(_, _, pk, sk) => (pk, sk)
    }

    def mkSortKeyExpression(sortKeyName: String) =
      sortKeyQuery match {
        case SortKeyQuery.EqualTo(value) =>
          val placeholder = ":t1"
          Expression(
            s"#$sortKeyName = $placeholder",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(placeholder -> value.asAttributeValue)
          ).some

        case SortKeyQuery.LessThan(value) =>
          val placeholder = ":t1"
          Expression(
            s"#$sortKeyName < $placeholder",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(placeholder -> value.asAttributeValue)
          ).some

        case SortKeyQuery.LessOrEqualTo(value) =>
          val placeholder = ":t1"
          Expression(
            s"#$sortKeyName <= $placeholder",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(placeholder -> value.asAttributeValue)
          ).some

        case SortKeyQuery.GreaterThan(value) =>
          val placeholder = ":t1"
          Expression(
            s"#$sortKeyName > $placeholder",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(placeholder -> value.asAttributeValue)
          ).some

        case SortKeyQuery.GreaterOrEqualTo(value) =>
          val placeholder = ":t1"
          Expression(
            s"#$sortKeyName >= $placeholder",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(placeholder -> value.asAttributeValue)
          ).some

        case SortKeyQuery.Between(from, to) =>
          val placeholder1 = ":t1"
          val placeholder2 = ":t2"
          Expression(
            s"#$sortKeyName BETWEEN $placeholder1 AND $placeholder2",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(
              placeholder1 -> from.asAttributeValue,
              placeholder2 -> to.asAttributeValue
            )
          ).some

        case SortKeyQuery.BeginsWith(value) =>
          val placeholder = ":t1"
          Expression(
            s"begins_with(#$sortKeyName, $placeholder)",
            Map(s"#$sortKeyName" -> sortKeyName),
            Map(placeholder -> value.asAttributeValue)
          ).some

        case _ =>
          None
      }

    val partitionKeyAV = Encoder[P].write(partitionKey)
    val placeholder = ":t0"

    val partitionKeyExpression = Expression(
      s"#${partitionKeySchema.name} = $placeholder",
      Map(s"#${partitionKeySchema.name}" -> partitionKeySchema.name),
      Map(placeholder -> partitionKeyAV)
    )

    val optSortKeyExpression = for {
      sortKey <- optSortKeySchema
      sortKeyExp <- mkSortKeyExpression(sortKey.name)
    } yield sortKeyExp

    Monoid.maybeCombine(partitionKeyExpression, optSortKeyExpression)
  }
}

object Query {
  def apply[P: Encoder](
    partitionKey: P
  ): Query[P, Nothing] =
    Query[P, Nothing](
      partitionKey,
      SortKeyQuery.Empty[Nothing](),
      Expression.empty
    )

  def apply[P: Encoder, S: Encoder](
    partitionKey: P,
    sortKeyQuery: SortKeyQuery[S]
  ): Query[P, S] = Query(partitionKey, sortKeyQuery, Expression.empty)

  def apply[P: Encoder](
    partitionKey: P,
    filter: Expression
  ): Query[P, Nothing] =
    Query[P, Nothing](partitionKey, SortKeyQuery.Empty[Nothing](), filter)
}

trait DynamoDbType {
  def toScalarAttributeType: ScalarAttributeType =
    this match {
      case DynamoDbType.B =>
        ScalarAttributeType.B

      case DynamoDbType.S =>
        ScalarAttributeType.S

      case DynamoDbType.N =>
        ScalarAttributeType.N

      case _ =>
        ScalarAttributeType.UNKNOWN_TO_SDK_VERSION
    }
}

object DynamoDbType {
  case object BOOL extends DynamoDbType //boolean
  case object B extends DynamoDbType //binary
  case object BS extends DynamoDbType //binary set
  case object L extends DynamoDbType //list
  case object M extends DynamoDbType //map
  case object N extends DynamoDbType //number
  case object NS extends DynamoDbType //number set
  case object NULL extends DynamoDbType //null
  case object S extends DynamoDbType //string
  case object SS extends DynamoDbType //string set

  implicit val dynamoDbTypeShow: Show[DynamoDbType] =
    Show.fromToString[DynamoDbType]
}
