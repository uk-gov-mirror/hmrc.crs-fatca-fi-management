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

import com.softwaremill.quicklens._
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito.{reset, verify, verifyNoInteractions}
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.http.Status.OK
import play.api.inject.bind
import play.api.libs.json.{JsValue, Json, Writes}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.crsfatcafimanagement.SpecBase
import uk.gov.hmrc.crsfatcafimanagement.auth.{AllowAllAuthAction, FakeAllowAllAuthAction}
import uk.gov.hmrc.crsfatcafimanagement.connectors.CADXConnector
import uk.gov.hmrc.crsfatcafimanagement.generators.Generators
import uk.gov.hmrc.crsfatcafimanagement.models.CADXRequestModels._
import uk.gov.hmrc.crsfatcafimanagement.models.error.ErrorDetails
import uk.gov.hmrc.crsfatcafimanagement.models.errors.CreateSubmissionError
import uk.gov.hmrc.crsfatcafimanagement.models.{FIDetail, ViewFIDetailsResponse}
import uk.gov.hmrc.crsfatcafimanagement.services.{AuditService, CADXSubmissionService}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import scala.concurrent.{ExecutionContext, Future}

class FIManagementControllerSpec extends SpecBase with Generators with BeforeAndAfterEach {

  val mockAuthConnector: AuthConnector                 = mock[AuthConnector]
  val mockCADXConnector: CADXConnector                 = mock[CADXConnector]
  val mockCADXSubmissionService: CADXSubmissionService = mock[CADXSubmissionService]
  val mockAuditService: AuditService                   = mock[AuditService]

  override lazy val app: Application = applicationBuilder()
    .overrides(
      bind[CADXConnector].toInstance(mockCADXConnector),
      bind[CADXSubmissionService].toInstance(mockCADXSubmissionService),
      bind[AuditService].toInstance(mockAuditService),
      bind[AuthConnector].toInstance(mockAuthConnector),
      bind[AllowAllAuthAction].to[FakeAllowAllAuthAction]
    )
    .build()

  override def beforeEach(): Unit = {
    reset(
      mockAuthConnector,
      mockCADXConnector,
      mockCADXSubmissionService,
      mockAuditService
    )

    super.beforeEach()
  }

  private val errorStatusCodes = Table(
    "connectorErrorCode",
    FORBIDDEN,
    NOT_FOUND,
    SERVICE_UNAVAILABLE,
    INTERNAL_SERVER_ERROR,
    METHOD_NOT_ALLOWED
  )

  val fiDetailsRequestJson: JsValue = Json.parse(
    """
      |{
      |  "SubscriptionID": "123456789012345",
      |  "FIID": "FI1234567890123",
      |  "FIName": "Financial Institution",
      |  "TINDetails": [],
      |  "GIIN": "TIN123456789",
      |  "IsFIUser": false,
      |  "PrimaryContactDetails": {
      |    "PhoneNumber": "07123456789",
      |    "ContactName": "John Doe",
      |    "EmailAddress": "john.doe@example.com"
      |  },
      |  "SecondaryContactDetails": {
      |    "PhoneNumber": "07876543210",
      |    "ContactName": "Jane Doe",
      |    "EmailAddress": "jane.doe@example.com"
      |  },
      |  "AddressDetails": {
      |    "AddressLine1": "100 Sutton Street",
      |    "AddressLine2": "Wokingham",
      |    "AddressLine3": "Surrey",
      |    "AddressLine4": "London",
      |    "PostalCode": "DH14EJ",
      |    "CountryCode": "GB"
      |  }
      |}""".stripMargin
  )

  val invalidFiDetailsRequestJson: JsValue = Json.parse(
    """
      |{
      |}""".stripMargin
  )

  "FIManagementController" - {

    "listFinancialInstitutions" - {

      "must return OK when connector returns FIs" in {
        forAll(arbitrary[ViewFIDetailsResponse], arbitrary[FIDetail]) {
          (response, fiDetail) =>
            val subscriptionId = fiDetail.SubscriptionID

            val stubbedResponse =
              response
                .modify(_.ViewFIDetails.ResponseDetails.FIDetails)
                .setTo(List(fiDetail))

            when(
              mockCADXConnector
                .listFinancialInstitutions(
                  mockitoEq(subscriptionId)
                )(
                  any[HeaderCarrier](),
                  any[ExecutionContext]()
                )
            ).thenReturn(
              Future.successful(
                HttpResponse(
                  OK,
                  Json.toJson(stubbedResponse),
                  Map.empty
                )
              )
            )

            val request =
              FakeRequest(
                GET,
                routes.FIManagementController
                  .listFinancialInstitutions(subscriptionId)
                  .url
              )

            val result = route(app, request).value

            status(result) mustEqual OK
            contentAsJson(result) mustBe Json.toJson(stubbedResponse)
        }
      }

      "must handle UNPROCESSABLE_ENTITY error response returned by the connector" in {
        forAll(arbitrary[ErrorDetails], arbitrary[FIDetail]) {
          (errorResponse, fiDetail) =>
            val subscriptionId = fiDetail.SubscriptionID

            when(
              mockCADXConnector
                .listFinancialInstitutions(
                  any[String]
                )(
                  any[HeaderCarrier](),
                  any[ExecutionContext]()
                )
            ).thenReturn(
              Future.successful(
                HttpResponse(
                  UNPROCESSABLE_ENTITY,
                  Json.toJson(errorResponse),
                  Map.empty
                )
              )
            )

            val request =
              FakeRequest(
                GET,
                routes.FIManagementController
                  .listFinancialInstitutions(subscriptionId)
                  .url
              )

            val result = route(app, request).value

            status(result) mustEqual UNPROCESSABLE_ENTITY
            contentAsJson(result) mustBe Json.toJson(errorResponse)
        }
      }

      forAll(errorStatusCodes) {
        errorStatusCode =>
          s"must return $errorStatusCode when connector returns $errorStatusCode" in {
            forAll(validSubscriptionID) {
              subscriptionId =>
                when(
                  mockCADXConnector
                    .listFinancialInstitutions(
                      any[String]
                    )(
                      any[HeaderCarrier](),
                      any[ExecutionContext]()
                    )
                ).thenReturn(
                  Future.successful(
                    HttpResponse(
                      errorStatusCode,
                      Json.obj(),
                      Map.empty
                    )
                  )
                )

                val request =
                  FakeRequest(
                    GET,
                    routes.FIManagementController
                      .listFinancialInstitutions(subscriptionId)
                      .url
                  )

                val result = route(app, request).value

                status(result) mustEqual errorStatusCode
            }
          }
      }
    }

    "viewFinancialInstitution" - {

      "must return OK when connector returns requested FI" in {
        forAll(arbitrary[ViewFIDetailsResponse], arbitrary[FIDetail]) {
          (response, fiDetail) =>
            val subscriptionId = fiDetail.SubscriptionID
            val fiId           = fiDetail.FIID

            val stubbedResponse =
              response
                .modify(_.ViewFIDetails.ResponseDetails.FIDetails)
                .setTo(List(fiDetail))

            when(
              mockCADXConnector
                .viewFinancialInstitution(
                  mockitoEq(subscriptionId),
                  mockitoEq(fiId)
                )(
                  any[HeaderCarrier](),
                  any[ExecutionContext]()
                )
            ).thenReturn(
              Future.successful(
                HttpResponse(
                  OK,
                  Json.toJson(stubbedResponse),
                  Map.empty
                )
              )
            )

            val request =
              FakeRequest(
                GET,
                routes.FIManagementController
                  .viewFinancialInstitution(subscriptionId, fiId)
                  .url
              )

            val result = route(app, request).value

            status(result) mustEqual OK
            contentAsJson(result) mustBe Json.toJson(stubbedResponse)
        }
      }

      "must handle UNPROCESSABLE_ENTITY error response returned by the connector" in {
        forAll(arbitrary[ErrorDetails], arbitrary[FIDetail]) {
          (errorResponse, fiDetail) =>
            val subscriptionId = fiDetail.SubscriptionID
            val fiId           = fiDetail.FIID

            when(
              mockCADXConnector
                .viewFinancialInstitution(
                  any[String],
                  any[String]
                )(
                  any[HeaderCarrier](),
                  any[ExecutionContext]()
                )
            ).thenReturn(
              Future.successful(
                HttpResponse(
                  UNPROCESSABLE_ENTITY,
                  Json.toJson(errorResponse),
                  Map.empty
                )
              )
            )

            val request =
              FakeRequest(
                GET,
                routes.FIManagementController
                  .viewFinancialInstitution(subscriptionId, fiId)
                  .url
              )

            val result = route(app, request).value

            status(result) mustEqual UNPROCESSABLE_ENTITY
            contentAsJson(result) mustBe Json.toJson(errorResponse)
        }
      }

      forAll(errorStatusCodes) {
        errorStatusCode =>
          s"must return $errorStatusCode when connector returns $errorStatusCode" in {
            forAll(arbitrary[FIDetail]) {
              fiDetail =>
                val subscriptionId = fiDetail.SubscriptionID
                val fiId           = fiDetail.FIID

                when(
                  mockCADXConnector
                    .viewFinancialInstitution(
                      any[String],
                      any[String]
                    )(
                      any[HeaderCarrier](),
                      any[ExecutionContext]()
                    )
                ).thenReturn(
                  Future.successful(
                    HttpResponse(
                      errorStatusCode,
                      Json.obj(),
                      Map.empty
                    )
                  )
                )

                val request =
                  FakeRequest(
                    GET,
                    routes.FIManagementController
                      .viewFinancialInstitution(subscriptionId, fiId)
                      .url
                  )

                val result = route(app, request).value

                status(result) mustEqual errorStatusCode
            }
          }
      }
    }

    "createFinancialInstitution" - {

      val responseJson =
        """{
          |  "ResponseDetails": {
          |    "processingDate": "2001-12-17T09:30:47z",
          |    "ReturnParameters": {
          |      "Key": "POID",
          |      "Value": "177993886"
          |    }
          |  }
          |}
          |""".stripMargin

      "must return OK when UpdateSubscription was successful" in {

        when(
          mockCADXSubmissionService
            .createOrUpdateFI(
              any[CreateRequestDetails]()
            )(
              any[HeaderCarrier](),
              any[ExecutionContext](),
              any[Writes[FIManagement[FIDetailsRequest[CreateRequestDetails]]]]
            )
        ).thenReturn(
          Future.successful(
            HttpResponse(
              OK,
              responseJson,
              Map.empty
            )
          )
        )

        val request =
          FakeRequest(
            POST,
            routes.FIManagementController
              .createFinancialInstitution()
              .url
          ).withJsonBody(fiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual OK
        contentAsJson(result) mustBe Json.parse(responseJson)
      }

      "must return 500 with a json validation error when receiving invalid json" in {

        val request =
          FakeRequest(
            POST,
            routes.FIManagementController
              .createFinancialInstitution()
              .url
          ).withJsonBody(invalidFiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual INTERNAL_SERVER_ERROR
      }
    }

    "updateFinancialInstitution" - {

      val responseJson =
        """{
          |  "ResponseDetails": {
          |    "processingDate": "2001-12-17T09:30:47z",
          |  }
          |}
          |""".stripMargin

      "must return OK when UpdateSubscription was successful" in {

        when(
          mockCADXSubmissionService
            .createOrUpdateFI(
              any[UpdateRequestDetails]()
            )(
              any[HeaderCarrier](),
              any[ExecutionContext](),
              any[Writes[FIManagement[FIDetailsRequest[UpdateRequestDetails]]]]
            )
        ).thenReturn(
          Future.successful(
            HttpResponse(
              OK,
              responseJson,
              Map.empty
            )
          )
        )

        val request =
          FakeRequest(
            PUT,
            routes.FIManagementController
              .updateFinancialInstitution()
              .url
          ).withJsonBody(fiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual OK
      }

      "must return 500 with a json validation error when receiving invalid json" in {

        val request =
          FakeRequest(
            PUT,
            routes.FIManagementController
              .updateFinancialInstitution()
              .url
          ).withJsonBody(invalidFiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual INTERNAL_SERVER_ERROR
      }
    }

    "removeFinancialInstitution" - {

      val removeFiDetailsRequestJson: JsValue = Json.parse(
        """
          |{
          |  "SubscriptionID": "123456789012345",
          |  "FIID": "FI1234567890123",
          |  "FIName": "Financial Institution"
          |}""".stripMargin
      )

      "must return OK and send the correct audit event when removal request was successful" in {

        when(
          mockCADXSubmissionService
            .removeFI(
              any[RemoveRequestDetails]()
            )(
              any[HeaderCarrier](),
              any[ExecutionContext]()
            )
        ).thenReturn(
          Future.successful(Right(()))
        )

        val request =
          FakeRequest(
            POST,
            routes.FIManagementController
              .removeFinancialInstitution()
              .url
          ).withJsonBody(removeFiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual OK

        verify(mockAuditService)
          .sendRemoveFinancialInstitution(
            mockitoEq("FI1234567890123"),
            mockitoEq("123456789012345")
          )(
            any[HeaderCarrier]()
          )
      }

      "must return 500 and not send an audit event when removal request fails" in {

        when(
          mockCADXSubmissionService
            .removeFI(
              any[RemoveRequestDetails]()
            )(
              any[HeaderCarrier](),
              any[ExecutionContext]()
            )
        ).thenReturn(
          Future.successful(
            Left(CreateSubmissionError(500))
          )
        )

        val request =
          FakeRequest(
            POST,
            routes.FIManagementController
              .removeFinancialInstitution()
              .url
          ).withJsonBody(removeFiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual INTERNAL_SERVER_ERROR

        verifyNoInteractions(mockAuditService)
      }

      "must return 500 with a json validation error when receiving invalid json" in {

        val request =
          FakeRequest(
            POST,
            routes.FIManagementController
              .removeFinancialInstitution()
              .url
          ).withJsonBody(invalidFiDetailsRequestJson)

        val result = route(app, request).value

        status(result) mustEqual INTERNAL_SERVER_ERROR

        verifyNoInteractions(mockAuditService)
      }
    }
  }

}
