/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.crsfatcafimanagement.services

import org.mockito.Mockito.{mockingDetails, reset}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.crsfatcafimanagement.models.audit.RemoveFinancialInstitution
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.DefaultAuditConnector

import scala.concurrent.ExecutionContext

class AuditServiceSpec extends AnyFreeSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  implicit private val ec: ExecutionContext =
    ExecutionContext.global

  implicit private val hc: HeaderCarrier =
    HeaderCarrier()

  private val mockAuditConnector =
    mock[DefaultAuditConnector]

  private val service =
    new AuditService(mockAuditConnector)

  override def beforeEach(): Unit = {
    reset(mockAuditConnector)
    super.beforeEach()
  }

  "AuditService" - {

    "sendRemoveFinancialInstitution" - {

      "must send the correct RemoveFinancialInstitution audit event" in {

        val expectedEvent =
          RemoveFinancialInstitution(
            financialInstitutionId = "FI123456",
            fatcaId = "FATCA123456"
          )

        service.sendRemoveFinancialInstitution(
          financialInstitutionId = expectedEvent.financialInstitutionId,
          fatcaId = expectedEvent.fatcaId
        )

        val invocations =
          mockingDetails(mockAuditConnector).getInvocations

        invocations.size() mustBe 1

        val invocation =
          invocations.iterator().next()

        invocation.getArgument[String](0) mustBe
          "RemoveFinancialInstitution"

        invocation.getArgument[RemoveFinancialInstitution](1) mustBe
          expectedEvent
      }
    }
  }

}
