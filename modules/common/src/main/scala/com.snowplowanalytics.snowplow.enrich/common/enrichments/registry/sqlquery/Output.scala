/*
 * Copyright (c) 2012-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package enrichments.registry.sqlquery

import java.sql.{ResultSet, ResultSetMetaData}

import scala.collection.mutable.ListBuffer

import cats.data.EitherT
import cats.implicits._
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import org.joda.time.DateTime

/**
 * Container class for output preferences.
 * Describes how to transform data fetched from DB into derived contexts
 * @param json JSON-preferences
 * @param expectedRows specifies amount of expected rows
 */
final case class Output(json: JsonOutput, expectedRows: String) {
  import Output._

  /** `expectedRows` object converted from String */
  val expectedRowsMode = expectedRows match {
    case "EXACTLY_ONE" => ExactlyOne
    case "AT_MOST_ONE" => AtMostOne
    case "AT_LEAST_ONE" => AtLeastOne
    case "AT_LEAST_ZERO" => AtLeastZero
    case other =>
      throw new Exception(
        s"SQL Query Enrichment: [$other] is unknown value for expectedRows property"
      )
  }

  /** `describe` object converted from String */
  val describeMode = json.describeMode

  /**
   * Convert list of rows fetched from DB into list (probably empty or single-element) of
   * Self-describing JSON objects (contexts). Primary function of class
   * @param resultSet rows fetched from DB
   * @return list of successful Self-describing JSON Objects or error
   */
  def convert(resultSet: ResultSet): EitherThrowable[List[Json]] = {
    val buffer = ListBuffer.empty[EitherThrowable[JsonObject]]
    while (resultSet.next()) { buffer += parse(resultSet) }
    val parsedJsons = buffer.result().sequence
    resultSet.close()

    for {
      jsons <- parsedJsons
      contexts <- envelope(jsons)
    } yield contexts
  }

  /**
   * Validate output according to expectedRows and describe
   * (attach Schema URI) to context according to json.describes.
   * @param jsons list of JSON Objects derived from SQL rows (row is always JSON Object)
   * @return validated list of described JSONs
   */
  def envelope(jsons: List[JsonObject]): EitherThrowable[List[Json]] =
    (describeMode, expectedRowsMode) match {
      case (AllRows, AtLeastOne) =>
        AtLeastOne
          .collect(jsons)
          .map { jobjs =>
            describe(Json.arr(jobjs.map(Json.fromJsonObject): _*))
          }
          .map(List(_))
      case (AllRows, AtLeastZero) =>
        AtLeastZero
          .collect(jsons)
          .map { jobjs =>
            describe(Json.arr(jobjs.map(Json.fromJsonObject): _*))
          }
          .map(List(_))
      case (AllRows, single) =>
        single.collect(jsons).map(_.headOption.map(js => describe(Json.fromJsonObject(js))).toList)
      case (EveryRow, any) => any.collect(jsons).map(_.map(js => describe(Json.fromJsonObject(js))))
    }

  /**
   * Transform ResultSet35 (single row) fetched from DB into a JSON Object
   * Each column maps to an Object's key with name transformed by json.propertyNames
   * And value transformed using [[JsonOutput#getValue]]
   * @param resultSet single column result
   * @return successful raw JSON Object or throwable in case of error
   */
  def parse(resultSet: ResultSet): EitherThrowable[JsonObject] =
    json.transform(resultSet)

  /**
   * Attach Iglu URI to JSON making it Self-describing JSON data
   * @param data JSON value to describe (object or array)
   * @return Self-describing JSON object
   */
  def describe(data: Json): Json =
    Json.obj(
      "schema" := json.schema,
      "data" := data
    )
}

object Output {

  /**
   * ADT specifying whether the schema is the self-describing schema for all
   * rows returned by the query, or whether the schema should be attached to
   * each of the returned rows.
   * Processing in [[Output#envelope]]
   */
  sealed trait DescribeMode

  /**
   * Box all returned rows - i.e. one context will always be added to
   * derived_contexts, regardless of how many rows that schema contains
   * Can be List(JArray) (signle) or List(JObject) (signle) or Nil
   */
  case object AllRows extends DescribeMode

  /**
   * Attached Schema URI to each returned row - so e.g. if 3 rows are returned,
   * 3 contexts with this same schema will be added to derived_contexts
   * Can be List(JObject, JObject...) (multiple) | Nil
   */
  case object EveryRow extends DescribeMode

  /**
   * ADT specifying what amount of rows are expecting from DB
   */
  sealed trait ExpectedRowsMode {

    /**
     * Validate some amount of rows against predefined expectation
     * @param resultSet JSON objects fetched from DB
     * @return same list of JSON object as right disjunction if amount
     *         of rows matches expectation or [[InvalidDbResponse]] as
     *         left disjunction if amount is lower or higher than expected
     */
    def collect(resultSet: List[JsonObject]): EitherThrowable[List[JsonObject]]
  }

  /**
   * Exactly one row is expected. 0 or 2+ rows will throw an error, causing the entire event to fail
   * processing
   */
  case object ExactlyOne extends ExpectedRowsMode {
    def collect(resultSet: List[JsonObject]): EitherThrowable[List[JsonObject]] =
      resultSet match {
        case List(one) => List(one).asRight
        case _ =>
          InvalidDbResponse(s"SQL Query Enrichment: exactly one row was expected").asLeft
      }
  }

  /** Either one or zero rows is expected. 2+ rows will throw an error */
  case object AtMostOne extends ExpectedRowsMode {
    def collect(resultSet: List[JsonObject]): EitherThrowable[List[JsonObject]] =
      resultSet match {
        case List(one) => List(one).asRight
        case List() => Nil.asRight
        case _ =>
          InvalidDbResponse(s"SQL Query Enrichment: at most one row was expected").asLeft
      }
  }

  /** Always successful */
  case object AtLeastZero extends ExpectedRowsMode {
    def collect(resultSet: List[JsonObject]): EitherThrowable[List[JsonObject]] =
      resultSet.asRight
  }

  /** More that 1 rows are expected 0 rows will throw an error */
  case object AtLeastOne extends ExpectedRowsMode {
    def collect(resultSet: List[JsonObject]): EitherThrowable[List[JsonObject]] =
      resultSet match {
        case Nil =>
          InvalidDbResponse(s"SQL Query Enrichment: at least one row was expected. 0 given instead").asLeft
        case other => other.asRight
      }
  }
}

/**
 * Handles JSON-specific output (actually, nothing here is JSON-specific, unlike API Request
 * Enrichment, so all these properties can go into primary
 * Output class as they can be used for *any* output)
 */
final case class JsonOutput(
  schema: String,
  describes: String,
  propertyNames: String
) {
  import JsonOutput._
  import Output._

  val describeMode: DescribeMode = describes match {
    case "ALL_ROWS" => AllRows
    case "EVERY_ROW" => EveryRow
    case p => throw new Exception(s"Describe [$p] is not allowed")
  }

  val propertyNameMode = propertyNames match {
    case "AS_IS" => AsIs
    case "CAMEL_CASE" => CamelCase
    case "PASCAL_CASE" => PascalCase
    case "SNAKE_CASE" => SnakeCase
    case "LOWER_CASE" => LowerCase
    case "UPPER_CASE" => UpperCase
    case p => throw new Exception(s"PropertyName [$p] is not allowed")
  }

  /**
   * Transform fetched from DB row (as ResultSet) into JSON object
   * All column names are mapped to object keys using propertyNames
   * @param resultSet column fetched from DB
   * @return JSON object as right disjunction in case of success or throwable as left disjunction in
   * case of any error
   */
  def transform(resultSet: ResultSet): EitherThrowable[JsonObject] = {
    val fields: List[Either[Throwable, (String, Json)]] = (for {
      rsMeta <- EitherT.fromEither[List](getMetaData(resultSet))
      idx <- EitherT(getColumnCount(rsMeta).map((x: Int) => (1 to x).toList).sequence)
      colLabel <- EitherT.fromEither[List](getColumnLabel(idx, rsMeta))
      colType <- EitherT.fromEither[List](getColumnType(idx, rsMeta))
      value <- EitherT.fromEither[List](getColumnValue(colType, idx, resultSet))
    } yield propertyNameMode.transform(colLabel) -> value).value

    fields.sequence.map(x => JsonObject(x: _*))
  }

}

object JsonOutput {

  /** ADT specifying how to transform key names */
  sealed trait PropertyNameMode {
    def transform(key: String): String
  }

  /** Some_Column to Some_Column */
  case object AsIs extends PropertyNameMode {
    def transform(key: String): String = key
  }

  /** some_column to someColumn */
  case object CamelCase extends PropertyNameMode {
    def transform(key: String): String =
      "_([a-z\\d])".r.replaceAllIn(key, _.group(1).toUpperCase)
  }

  /** some_column to SomeColumn */
  case object PascalCase extends PropertyNameMode {
    def transform(key: String): String =
      "_([a-z\\d])".r.replaceAllIn(key, _.group(1).toUpperCase).capitalize
  }

  /** SomeColumn to some_column */
  case object SnakeCase extends PropertyNameMode {
    def transform(key: String): String =
      "[A-Z\\d]".r.replaceAllIn(key, "_" + _.group(0).toLowerCase())
  }

  /** SomeColumn to somecolumn */
  case object LowerCase extends PropertyNameMode {
    def transform(key: String): String = key.toLowerCase
  }

  /** SomeColumn to SOMECOLUMN */
  case object UpperCase extends PropertyNameMode {
    def transform(key: String): String = key.toUpperCase
  }

  /** Map of datatypes to JSON-generator functions */
  val resultsetGetters: Map[String, Object => Json] = Map(
    "java.lang.Integer" -> ((obj: Object) => Json.fromInt(obj.asInstanceOf[Int])),
    "java.lang.Long" -> ((obj: Object) => Json.fromLong(obj.asInstanceOf[Long])),
    "java.lang.Boolean" -> ((obj: Object) => Json.fromBoolean(obj.asInstanceOf[Boolean])),
    "java.lang.Double" -> ((obj: Object) => Json.fromDoubleOrNull(obj.asInstanceOf[Double])),
    "java.lang.Float" -> ((obj: Object) => Json.fromDoubleOrNull(obj.asInstanceOf[Float].toDouble)),
    "java.lang.String" -> ((obj: Object) => Json.fromString(obj.asInstanceOf[String])),
    "java.sql.Date" -> (
      (obj: Object) => Json.fromString(new DateTime(obj.asInstanceOf[java.sql.Date]).toString)
    )
  )

  /** Lift failing ResultSet#getMetaData into scalaz disjunction with Throwable as left-side */
  def getMetaData(rs: ResultSet): EitherThrowable[ResultSetMetaData] =
    Either.catchNonFatal(rs.getMetaData)

  /**
   * Lift failing ResultSetMetaData#getColumnCount into scalaz disjunction with Throwable as
   * left-side
   */
  def getColumnCount(rsMeta: ResultSetMetaData): EitherThrowable[Int] =
    Either.catchNonFatal(rsMeta.getColumnCount)

  /**
   * Lift failing ResultSetMetaData#getColumnLabel into scalaz disjunction with Throwable as
   * left-side
   */
  def getColumnLabel(column: Int, rsMeta: ResultSetMetaData): EitherThrowable[String] =
    Either.catchNonFatal(rsMeta.getColumnLabel(column))

  /**
   * Lift failing ResultSetMetaData#getColumnClassName into scalaz disjunction with Throwable as
   * left-side
   */
  def getColumnType(column: Int, rsMeta: ResultSetMetaData): EitherThrowable[String] =
    Either.catchNonFatal(rsMeta.getColumnClassName(column))

  /**
   * Get value from ResultSet using column number
   * @param datatype stringified type representing real type
   * @param columnIdx column's number in table
   * @param rs result set fetched from DB
   * @return JSON in case of success or Throwable in case of SQL error
   */
  def getColumnValue(
    datatype: String,
    columnIdx: Int,
    rs: ResultSet
  ): EitherThrowable[Json] =
    for {
      value <- Either.catchNonFatal(rs.getObject(columnIdx)).map(Option.apply)
    } yield value.map(getValue(_, datatype)).getOrElse(Json.Null)

  /**
   * Transform value from AnyRef using stringified type hint
   * @param anyRef AnyRef extracted from ResultSet
   * @param datatype stringified type representing AnyRef's real type
   * @return AnyRef converted to JSON
   */
  def getValue(anyRef: AnyRef, datatype: String): Json =
    if (anyRef == null) Json.Null
    else {
      val converter = resultsetGetters.getOrElse(datatype, parseObject)
      converter(anyRef)
    }

  /**
   * Default method to parse unknown column type. First try to parse as JSON Object (PostgreSQL
   * JSON doesn't have a loader for JSON) if not successful parse as JSON String.
   * This method has significant disadvantage, since it can parse string "12 books" as JInt(12),
   * but I don't know better way to handle PostgreSQL JSON.
   */
  val parseObject: Object => Json = (obj) => {
    val string = obj.toString
    parse(string) match {
      case Right(js) => js
      case _ => Json.fromString(string)
    }
  }
}
