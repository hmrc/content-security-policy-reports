/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.contentsecuritypolicyreports.controllers

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.Helpers._

class ReportControllerSpec
  extends AnyWordSpec
     with Matchers
     with GuiceOneServerPerSuite
     with ScalaFutures
     with IntegrationPatience {


  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "metrics.jvm"     -> false,
        "metrics.enabled" -> false
      )
      .build()

  private val client = app.injector.instanceOf[WSClient]
  private val baseUrl = s"http://localhost:$port/content-security-policy-reports"

  "POST /:service/report" should {
    "return 200 when receiving a valid CSP Report" in {
      val response =
        client.url(s"$baseUrl/test-service")
          .withHttpHeaders(CONTENT_TYPE -> "application/csp-report")
          .post(
            s"""
               |{
               |  "csp-report": {
               |    "document-uri": "http://localhost",
               |    "referrer": "",
               |    "violated-directive": "script-src self",
               |    "effective-directive": "frame-src",
               |    "original-policy": "default-src  self; script-src self;",
               |    "blocked-uri": "https://google.com",
               |    "status-code": 200
               |  }
               |}
               |""".stripMargin
          ).futureValue

      response.status shouldBe 200
    }

    "return 400 when receiving an invalid CSP Report" in {
      val response =
        client.url(s"$baseUrl/test-service")
          .withHttpHeaders(CONTENT_TYPE -> "application/json")
          .post(
            s"""
               |{
               |  "key": "value"
               |}
               |""".stripMargin
          ).futureValue

      response.status shouldBe 400
    }
  }
}
