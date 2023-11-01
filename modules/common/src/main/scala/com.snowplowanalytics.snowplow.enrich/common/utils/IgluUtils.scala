/*
 * Copyright (c) 2014-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.utils

import cats.Monad
import cats.data.{EitherT, NonEmptyList}
import cats.effect.Clock
import cats.implicits._

import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._

import java.time.Instant

import org.apache.commons.codec.binary.Base64

import com.snowplowanalytics.iglu.client.{ClientError, IgluCirceClient}
import com.snowplowanalytics.iglu.client.validator.ValidatorError
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SchemaVer, SelfDescribingData}
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.snowplow.badrows._

import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentManager
import com.snowplowanalytics.snowplow.enrich.common.adapters.RawEvent

/**
 * Contain the functions to validate:
 *  - An unstructured event,
 *  - The input contexts of an event,
 *  - The contexts added by the enrichments.
 */
object IgluUtils {

  /**
   * Extract unstructured event (if any) and input contexts (if any) from input event
   * and validate them against their schema
   * @param enriched Contain the input Jsons
   * @param client Iglu client used to validate the SDJs
   * @param raw Raw input event, used only to put in the bad row in case of problem
   * @param processor Meta data to put in the bad row
   * @return Extracted unstructured event and input contexts if any and if everything valid,
   *         `BadRow.SchemaViolations` if something went wrong. For instance if the
   *         unstructured event is invalid and has a context that is invalid,
   *         the bad row will contain the 2 associated `FailureDetails.SchemaViolation`s
   */
  def extractAndValidateInputJsons[F[_]: Monad: RegistryLookup: Clock](
    enriched: EnrichedEvent,
    client: IgluCirceClient[F]
  ): F[EventExtractResult] =
      (for {
        contexts <- IgluUtils.extractAndValidateInputContexts(enriched, client)
        unstruct <- IgluUtils.extractAndValidateUnstructEvent(enriched, client)
      } yield (contexts, unstruct))
        .map { case (c, ue) =>
          val validationInfoContexts = (c.flatMap(_.toOption.flatMap(_.validationInfo)) ::: ue.map(_.flatMap(_.validationInfo)).toOption.flatten.toList).distinct
            .map(_.toSdj)
          val contexts = c.flatMap(_.toOption.map(_.sdj))
          val unstructEvent = ue.toOption.flatten.map(_.sdj)
          val validationFailures = (c.flatMap(_.left.toOption) ::: ue.left.toOption.toList).map(_.toSdj)
          EventExtractResult(
            contexts = contexts,
            unstructEvent = unstructEvent,
            validationInfoContexts = validationInfoContexts,
            validationFailures = validationFailures
          )
        }

  /**
   * Extract unstructured event from event and validate against its schema
   *  @param enriched Snowplow event from which to extract unstructured event (in String)
   *  @param client Iglu client used for SDJ validation
   *  @param field Name of the field containing the unstructured event, to put in the bad row
   *               in case of failure
   *  @param criterion Expected schema for the JSON containing the unstructured event
   *  @return Valid unstructured event if the input event has one
   */
  private[common] def extractAndValidateUnstructEvent[F[_]: Monad: RegistryLookup: Clock](
    enriched: EnrichedEvent,
    client: IgluCirceClient[F],
    field: String = "ue_properties",
    criterion: SchemaCriterion = SchemaCriterion("com.snowplowanalytics.snowplow", "unstruct_event", "jsonschema", 1, 0)
  ): F[Either[ValidationFailure, Option[SdjExtractResult]]] =
    (Option(enriched.unstruct_event) match {
      case Some(rawUnstructEvent) =>
        (for {
          // Validate input Json string and extract unstructured event
          unstruct <- extractInputData(rawUnstructEvent, field, criterion, client)
            .leftMap(v => ValidationFailure(v, SdjType.UnstructEvent, rawUnstructEvent))
          // Parse Json unstructured event as SelfDescribingData[Json]
          unstructSDJ <- parseAndValidateSDJ_sv(unstruct, client)
            .leftMap(v => ValidationFailure(v, SdjType.UnstructEvent, unstruct.noSpaces))
        } yield unstructSDJ.some).value
      case None =>
        Monad[F].pure(none.asRight)
    })

  /**
   * Extract list of custom contexts from event and validate each against its schema
   *  @param enriched Snowplow enriched event from which to extract custom contexts (in String)
   *  @param client Iglu client used for SDJ validation
   *  @param field Name of the field containing the contexts, to put in the bad row
   *               in case of failure
   *  @param criterion Expected schema for the JSON containing the contexts
   *  @return List will all contexts provided that they are all valid
   */
  private[common] def extractAndValidateInputContexts[F[_]: Monad: RegistryLookup: Clock](
    enriched: EnrichedEvent,
    client: IgluCirceClient[F],
    field: String = "contexts",
    criterion: SchemaCriterion = SchemaCriterion("com.snowplowanalytics.snowplow", "contexts", "jsonschema", 1, 0)
  ): F[List[Either[ValidationFailure, SdjExtractResult]]] =
    Option(enriched.contexts) match {
      case Some(rawContexts) =>
        for {
          // Validate input Json string and extract contexts
          contexts <- extractInputData(rawContexts, field, criterion, client)
            .map(_.asArray.get.toList).value // .get OK because SDJ wrapping the contexts valid
          // Parse and validate each SDJ and merge the errors
          contextsSDJ <-
            contexts match {
              case Left(v) => Monad[F].pure(List(ValidationFailure(v, SdjType.Context, rawContexts).asLeft))
              case Right(l) => l.map { c =>
                parseAndValidateSDJ_sv(c, client)
                  .leftMap(v => ValidationFailure(v, SdjType.Context, c.noSpaces))
                  .value
              }.sequence
            }
        } yield contextsSDJ
      case None =>
        Monad[F].pure(List.empty)
    }

  /**
   * Validate each context added by the enrichments against its schema
   *  @param client Iglu client used for SDJ validation
   *  @param sdjs List of enrichments contexts to be added to the enriched event
   *  @param raw Input event to put in the bad row if at least one context is invalid
   *  @param processor Meta data for the bad row
   *  @param enriched Partially enriched event to put in the bad row
   *  @return Unit if all the contexts are valid
   */
  private[common] def validateEnrichmentsContexts[F[_]: Monad: RegistryLookup: Clock](
    client: IgluCirceClient[F],
    sdjs: List[SelfDescribingData[Json]],
    raw: RawEvent,
    processor: Processor,
    enriched: EnrichedEvent
  ): EitherT[F, BadRow.EnrichmentFailures, Unit] =
    checkList(client, sdjs)
      .leftMap(
        _.map {
          case (schemaKey, clientError) =>
            val enrichmentInfo =
              FailureDetails.EnrichmentInformation(schemaKey, "enrichments-contexts-validation")
            FailureDetails.EnrichmentFailure(
              enrichmentInfo.some,
              FailureDetails.EnrichmentFailureMessage.IgluError(schemaKey, clientError)
            )
        }
      )
      .leftMap { enrichmentFailures =>
        EnrichmentManager.buildEnrichmentFailuresBadRow(
          enrichmentFailures,
          EnrichedEvent.toPartiallyEnrichedEvent(enriched),
          RawEvent.toRawEvent(raw),
          processor
        )
      }

  /** Used to extract .data for input custom contexts and input unstructured event */
  private def extractInputData[F[_]: Monad: RegistryLookup: Clock](
    rawJson: String,
    field: String, // to put in the bad row
    expectedCriterion: SchemaCriterion,
    client: IgluCirceClient[F]
  ): EitherT[F, FailureDetails.SchemaViolation, Json] =
    for {
      // Parse Json string with the SDJ
      json <- JsonUtils
                .extractJson(rawJson)
                .leftMap(e => FailureDetails.SchemaViolation.NotJson(field, rawJson.some, e))
                .toEitherT[F]
      // Parse Json as SelfDescribingData[Json] (which contains the .data that we want)
      sdj <- SelfDescribingData
               .parse(json)
               .leftMap(FailureDetails.SchemaViolation.NotIglu(json, _))
               .toEitherT[F]
      // Check that the schema of SelfDescribingData[Json] is the expected one
      _ <- if (validateCriterion(sdj, expectedCriterion))
             EitherT.rightT[F, FailureDetails.SchemaViolation](sdj)
           else
             EitherT
               .leftT[F, SelfDescribingData[Json]](
                 FailureDetails.SchemaViolation.CriterionMismatch(sdj.schema, expectedCriterion)
               )
      // Check that the SDJ holding the .data is valid
      _ <- check(client, sdj)
             .leftMap {
               case (schemaKey, clientError) =>
                 FailureDetails.SchemaViolation.IgluError(schemaKey, clientError)
             }
      // Extract .data of SelfDescribingData[Json]
      data <- EitherT.rightT[F, FailureDetails.SchemaViolation](sdj.data)
    } yield data

  /** Check that the schema of a SDJ matches the expected one */
  private def validateCriterion(sdj: SelfDescribingData[Json], criterion: SchemaCriterion): Boolean =
    criterion.matches(sdj.schema)

  /** Check that a SDJ is valid */
  private def check[F[_]: Monad: RegistryLookup: Clock](
    client: IgluCirceClient[F],
    sdj: SelfDescribingData[Json]
  ): EitherT[F, (SchemaKey, ClientError), Option[SchemaVer.Full]] =
    client
      .check(sdj)
      .leftMap((sdj.schema, _))

  /** Check a list of SDJs and merge the Iglu errors */
  private def checkList[F[_]: Monad: RegistryLookup: Clock](
    client: IgluCirceClient[F],
    sdjs: List[SelfDescribingData[Json]]
  ): EitherT[F, NonEmptyList[(SchemaKey, ClientError)], Unit] =
    EitherT {
      sdjs
        .map(check(client, _).toValidatedNel)
        .sequence
        .map(_.sequence_.toEither)
    }

  /** Parse a Json as a SDJ and check that it's valid */
  private def parseAndValidateSDJ_sv[F[_]: Monad: RegistryLookup: Clock]( // _sv for SchemaViolation
    json: Json,
    client: IgluCirceClient[F]
  ): EitherT[F, FailureDetails.SchemaViolation, SdjExtractResult] =
    for {
      sdj <- SelfDescribingData
               .parse(json)
               .leftMap(FailureDetails.SchemaViolation.NotIglu(json, _))
               .toEitherT[F]
      supersedingSchema <- check(client, sdj)
                             .leftMap {
                               case (schemaKey, clientError) =>
                                 FailureDetails.SchemaViolation
                                   .IgluError(schemaKey, clientError): FailureDetails.SchemaViolation

                             }
      validationInfo = supersedingSchema.map(s => ValidationInfo(sdj.schema, s))
      sdjUpdated = replaceSchemaVersion(sdj, validationInfo)
    } yield SdjExtractResult(sdjUpdated, validationInfo)

  private def replaceSchemaVersion(
    sdj: SelfDescribingData[Json],
    validationInfo: Option[ValidationInfo]
  ): SelfDescribingData[Json] =
    validationInfo match {
      case None => sdj
      case Some(s) => sdj.copy(schema = sdj.schema.copy(version = s.validatedWith))
    }

  case class ValidationInfo(originalSchema: SchemaKey, validatedWith: SchemaVer.Full) {
    def toSdj: SelfDescribingData[Json] =
      SelfDescribingData(ValidationInfo.schemaKey, (this: ValidationInfo).asJson)
  }

  object ValidationInfo {
    val schemaKey = SchemaKey("com.snowplowanalytics.iglu", "validation_info", "jsonschema", SchemaVer.Full(1, 0, 0))

    implicit val schemaVerFullEncoder: Encoder[SchemaVer.Full] =
      Encoder.encodeString.contramap(v => v.asString)

    implicit val validationInfoEncoder: Encoder[ValidationInfo] =
      deriveEncoder[ValidationInfo]
  }

  case class SdjExtractResult(sdj: SelfDescribingData[Json], validationInfo: Option[ValidationInfo])

  case class EventExtractResult(
    contexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    validationInfoContexts: List[SelfDescribingData[Json]],
    validationFailures: List[SelfDescribingData[Json]]
  )

  sealed trait SdjType
  object SdjType {
    case object UnstructEvent extends SdjType
    case object Context extends SdjType

    implicit val sdjTypeEncoder: Encoder[SdjType] = Encoder.encodeString.contramap {
      case UnstructEvent => "UnstructEvent"
      case Context => "Context"
    }
  }

  case class ValidationFailure(schemaViolation: FailureDetails.SchemaViolation, sdjType: SdjType, json: String) {
    def toSdj: SelfDescribingData[Json] =
      SelfDescribingData(ValidationFailure.schemaKey, (this: ValidationFailure).asJson)
  }

  object ValidationFailure {
    val schemaKey = SchemaKey("com.snowplowanalytics", "failure", "jsonschema", SchemaVer.Full(2, 0, 0))

    implicit val validationFailureEncoder: Encoder[ValidationFailure] =
      Encoder.instance { failure =>
        val errors = failure.schemaViolation match {
          case FailureDetails.SchemaViolation.IgluError(
              _, ClientError.ValidationError(ValidatorError.InvalidData(errors), _)
            ) => errors.toList.map(e => Json.obj("message" := e.message, "path" := e.path))
          case _ => Nil
        }
        val originalJsonB64 = Base64.encodeBase64String(failure.json.getBytes)
        Json.obj(
          "errors" := errors,
          "originalDataB64" := originalJsonB64
        )
      }
  }

  /** Build `BadRow.SchemaViolations` from a list of `FailureDetails.SchemaViolation`s */
  def buildSchemaViolationsBadRow(
    vs: NonEmptyList[FailureDetails.SchemaViolation],
    pee: Payload.PartiallyEnrichedEvent,
    re: Payload.RawEvent,
    processor: Processor
  ): BadRow.SchemaViolations =
    BadRow.SchemaViolations(
      processor,
      Failure.SchemaViolations(Instant.now(), vs),
      Payload.EnrichmentPayload(pee, re)
    )
}
