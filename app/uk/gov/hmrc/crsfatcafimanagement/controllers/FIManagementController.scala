/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.crsfatcafimanagement.controllers

import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}
import uk.gov.hmrc.crsfatcafimanagement.auth.AuthActionSets
import uk.gov.hmrc.crsfatcafimanagement.config.AppConfig
import uk.gov.hmrc.crsfatcafimanagement.connectors.CADXConnector
import uk.gov.hmrc.crsfatcafimanagement.models.CADXRequestModels.{CreateRequestDetails, RemoveRequestDetails, RequestDetails, UpdateRequestDetails}
import uk.gov.hmrc.crsfatcafimanagement.models.RequestType
import uk.gov.hmrc.crsfatcafimanagement.models.RequestType.{CREATE, UPDATE}
import uk.gov.hmrc.crsfatcafimanagement.models.error.ErrorDetails
import uk.gov.hmrc.crsfatcafimanagement.models.errors.CreateSubmissionError
import uk.gov.hmrc.crsfatcafimanagement.services.{AuditService, CADXSubmissionService}
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

class FIManagementController @Inject() (
  val config: AppConfig,
  authenticator: AuthActionSets,
  service: CADXSubmissionService,
  connector: CADXConnector,
  auditService: AuditService,
  override val controllerComponents: ControllerComponents
)(implicit executionContext: ExecutionContext)
    extends BackendController(controllerComponents)
    with Logging {

  def createFinancialInstitution(): Action[JsValue] = submitFinancialInstitutions(CREATE)

  def updateFinancialInstitution(): Action[JsValue] = submitFinancialInstitutions(UPDATE)

  private def submitFinancialInstitutions(requestType: RequestType): Action[JsValue] = authenticator.authenticateAll.async(parse.json) {
    implicit request =>
      def stripType(json: JsValue): JsValue = json match {
        case obj: JsObject => obj - "_type"
        case other         => other
      }
      val sanitizedBody = stripType(request.body)
      val validated: JsResult[RequestDetails] = requestType match {
        case CREATE => sanitizedBody.validate[CreateRequestDetails]
        case UPDATE => sanitizedBody.validate[UpdateRequestDetails]
        case _      => JsError(s"Unsupported requestType: $requestType")
      }
      validated
        .fold(
          invalid =>
            Future.successful {
              logger.warn(s" createSubmission Json Validation Failed: $invalid")
              InternalServerError("Json Validation Failed")
            },
          validReq =>
            service
              .createOrUpdateFI(validReq)
              .map(convertToResult)
        )
  }

  def removeFinancialInstitution(): Action[JsValue] = authenticator.authenticateAll.async(parse.json) {
    implicit request =>
      request.body
        .validate[RemoveRequestDetails]
        .fold(
          invalid =>
            Future.successful {
              logger.warn(s" removeFinancialInstitution Json Validation Failed: $invalid")
              InternalServerError("Json Validation Failed")
            },
          validReq =>
            service.removeFI(validReq).map {
              case Right(_) =>
                auditService.sendRemoveFinancialInstitution(validReq.FIID, validReq.SubscriptionID)
                Ok
              case Left(CreateSubmissionError(value)) =>
                logger.warn(s"CreateSubmissionError $value")
                InternalServerError(s"CreateSubmissionError $value")
            }
        )
  }

  def listFinancialInstitutions(subscriptionId: String): Action[AnyContent] =
    authenticator.authenticateAll.async {
      implicit request =>
        connector
          .listFinancialInstitutions(subscriptionId)
          .map(convertToResult)
    }

  def viewFinancialInstitution(subscriptionId: String, fiId: String): Action[AnyContent] =
    authenticator.authenticateAll.async {
      implicit request =>
        connector
          .viewFinancialInstitution(subscriptionId, fiId)
          .map(convertToResult)
    }

  private def convertToResult(httpResponse: HttpResponse): Result =
    httpResponse.status match {
      case OK        => Ok(httpResponse.body)
      case NOT_FOUND => NotFound(httpResponse.body)
      case UNPROCESSABLE_ENTITY =>
        logDownStreamError(httpResponse.body)
        UnprocessableEntity(httpResponse.body)
      case BAD_REQUEST =>
        logDownStreamError(httpResponse.body)
        BadRequest(httpResponse.body)
      case FORBIDDEN =>
        logDownStreamError(httpResponse.body)
        Forbidden(httpResponse.body)
      case SERVICE_UNAVAILABLE =>
        logDownStreamError(httpResponse.body)
        ServiceUnavailable(httpResponse.body)
      case METHOD_NOT_ALLOWED =>
        logDownStreamError(httpResponse.body)
        MethodNotAllowed(httpResponse.body)
      case _ =>
        logDownStreamError(httpResponse.body)
        InternalServerError(httpResponse.body)
    }

  private def logDownStreamError(body: String): Unit = {
    val error = Try(Json.parse(body).validate[ErrorDetails])
    error match {
      case Success(JsSuccess(value, _)) =>
        logger.warn(s"CADX error: ${value.ErrorDetail.sourceFaultDetail.map(_.detail.mkString)}")
      case _ =>
        logger.warn("CADX response is not a valid json")
    }
  }

}
