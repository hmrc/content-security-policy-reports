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

import ch.qos.logback.classic.{Level, Logger, spi}
import ch.qos.logback.core.read.ListAppender
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import org.slf4j.{LoggerFactory, MDC}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsNull, JsValue, Json}
import play.api.libs.ws.{WSClient, WSResponse, writeableOf_String}
import play.api.mvc.{DefaultActionBuilder, DefaultMessagesControllerComponents, MessagesControllerComponents}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.filters.csp.CSPReportActionBuilder

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

class ReportControllerSpec
  extends AnyWordSpec
     with Matchers
     with GuiceOneServerPerSuite
     with ScalaFutures
     with IntegrationPatience:

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .build()

  private val client = app.injector.instanceOf[WSClient]
  private val baseUrl = s"http://localhost:$port/content-security-policy-reports"

  private val reportBody = Json.obj(
    "documentURL" -> "https://example.com/page",
    "effectiveDirective" -> "script-src-elem",
    "blockedURL" -> "inline",
    "originalPolicy" -> "default-src 'self'; report-to csp-endpoint",
    "referrer" -> "",
    "sample" -> "alert('example')",
    "disposition" -> "enforce",
    "statusCode" -> 200,
    "sourceFile" -> "https://example.com/page",
    "lineNumber" -> 12,
    "columnNumber" -> 34
  )
  private val validReport = Json.obj(
    "type" -> "csp-violation",
    "age" -> 123,
    "url" -> "https://example.com/page",
    "user_agent" -> "original-page-agent",
    "body" -> reportBody
  )

  private def postReports(payload: JsValue, contentType: String = "application/reports+json", service: String = "test-service"): WSResponse =
    client
      .url(s"$baseUrl/report-to/$service")
      .withHttpHeaders(CONTENT_TYPE -> contentType, USER_AGENT -> "upload-agent")
      .post(Json.stringify(payload))
      .futureValue

  private def withLogs(test: ListAppender[spi.ILoggingEvent] => Any): Unit =
    val logger = LoggerFactory.getLogger("csp-report-logger").asInstanceOf[Logger]
    val appender = new ListAppender[spi.ILoggingEvent]:
      override def append(event: spi.ILoggingEvent): Unit =
        event.prepareForDeferredProcessing()
        super.append(event)
    appender.list = new CopyOnWriteArrayList[spi.ILoggingEvent]()
    appender.setContext(logger.getLoggerContext)
    appender.start()
    logger.addAppender(appender)
    try
      test(appender)
    finally
      logger.detachAppender(appender)
      appender.stop()

  private def assertRejectionLog(appender: ListAppender[spi.ILoggingEvent]): Unit =
    val events = appender.list.asScala.toList
    events should have size 1
    events.head.getLevel shouldBe Level.ERROR
    events.head.getFormattedMessage shouldBe
      "Rejected CSP Reporting API request: expected a JSON array of valid csp-violation reports"
    val mdc = events.head.getMDCPropertyMap.asScala.toMap
    mdc("reporting-service") shouldBe "test-service"
    mdc("reporting-api") shouldBe "report-to"
    mdc should not contain key("documentURL")
    mdc should not contain key("age")

  "POST /:service" should:
    "return 200 when receiving a valid CSP Report" in:
      val response =
        client
          .url(s"$baseUrl/test-service")
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

    "return 400 when receiving an invalid CSP Report" in:
      val response =
        client
          .url(s"$baseUrl/test-service")
          .withHttpHeaders(CONTENT_TYPE -> "application/json")
          .post(
            s"""
               |{
               |  "key": "value"
               |}
               |""".stripMargin
          ).futureValue

      response.status shouldBe 400
