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

class ReportToControllerSpec
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

  private val validLogMessage = Json.obj(
    "age" -> 123,
    "userAgent" -> "original-page-agent",
    "body" -> reportBody
  )

  private val secondBody = Json.obj(
    "documentURL" -> "https://example.com/second", "effectiveDirective" -> "img-src"
  )

  private val secondReport = Json.obj(
    "type" -> "csp-violation",
    "age" -> 123,
    "url" -> "https://example.com/page",
    "user_agent" -> "original-page-agent",
    "body" -> secondBody
  )

  private val validSecondLogMessage = Json.obj(
    "age" -> 123,
    "userAgent" -> "original-page-agent",
    "body" -> secondBody
  )

  private def postReports(payload: JsValue, contentType: String = "application/reports+json", service: String = "test-service"): WSResponse =
    client
      .url(s"$baseUrl/report-to/$service")
      .withHttpHeaders(CONTENT_TYPE -> contentType, USER_AGENT -> "upload-agent")
      .post(Json.stringify(payload))
      .futureValue

  private def withLogs(loggerName: String)(test: ListAppender[spi.ILoggingEvent] => Any): Unit =
    val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[Logger]
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

  "POST /report-to/:service" should:
    "return 200 when receiving a Reporting API CSP batch and logging it correctly" in:
      withLogs("csp-report-logger"): appender =>
        postReports(Json.arr(validReport)).status shouldBe 200
        val events = appender.list.asScala.toList
        events should have size 1
        Json.parse(events.head.getFormattedMessage) shouldBe validLogMessage
        val mdc = events.head.getMDCPropertyMap.asScala.toMap
        mdc should contain allOf (
          "reporting-service" -> "test-service",
          "reporting-api" -> "report-to",
          "age" -> "123",
          "user-agent" -> "original-page-agent",
          "documentURL" -> "https://example.com/page",
          "effectiveDirective" -> "script-src-elem",
          "blockedURL" -> "inline",
          "originalPolicy" -> "default-src 'self'; report-to csp-endpoint",
          "referrer" -> "",
          "sample" -> "alert('example')",
          "disposition" -> "enforce",
          "statusCode" -> "200",
          "sourceFile" -> "https://example.com/page",
          "lineNumber" -> "12",
          "columnNumber" -> "34"
        )

    "return 200 when receiving multiple CSP reports and logging it correctly" in:
      withLogs("csp-report-logger"): appender =>
        postReports(Json.arr(validReport, secondReport)).status shouldBe 200
        val events = appender.list.asScala.toList
        events should have size 2
        Json.parse(events.head.getFormattedMessage) shouldBe validLogMessage
        Json.parse(events(1).getFormattedMessage) shouldBe validSecondLogMessage

    "return 400 when receiving invalid reports with one error log and no violation logs" in:
      val invalidReports = Seq(
        validReport ++ Json.obj("type" -> "deprecation"),
        validReport ++ Json.obj("body" -> (reportBody - "documentURL"))
      )
      invalidReports.foreach: invalid =>
        withLogs("csp-report-logger"): cspLogAppender =>
          withLogs("uk.gov.hmrc.contentsecuritypolicyreports.controllers.ReportToController"): errorLogAppender =>
            val response = postReports(Json.arr(invalid))
            response.status shouldBe 400
            val cspEvents = cspLogAppender.list.asScala.toList
            cspEvents should have size 0
            val errorEvents = errorLogAppender.list.asScala.toList
            errorEvents should have size 1
            errorEvents.head.getLevel shouldBe Level.ERROR
            errorEvents.head.getFormattedMessage shouldBe "Rejected CSP Report-to API request from service test-service: expected a JSON array of valid csp-violation reports"
    
    "return 400 for malformed JSON with one error log and no violation logs" in:
      withLogs("csp-report-logger"): cspLogAppender =>
        withLogs("uk.gov.hmrc.contentsecuritypolicyreports.controllers.ReportToController"): errorLogAppender =>
          val response = client.url(s"$baseUrl/report-to/test-service")
            .withHttpHeaders(CONTENT_TYPE -> "application/reports+json")
            .post("[{not-json}]").futureValue
          response.status shouldBe 400
          val cspEvents = cspLogAppender.list.asScala.toList
          cspEvents should have size 0
          val errorEvents = errorLogAppender.list.asScala.toList
          errorEvents should have size 1
          errorEvents.head.getLevel shouldBe Level.ERROR
          errorEvents.head.getFormattedMessage shouldBe "Rejected CSP Report-to API request from service test-service: malformed JSON"

    "return 415 for unsupported or absent content types" in:
      Seq("application/json", "application/csp-report", "text/plain").foreach: contentType =>
        postReports(Json.arr(validReport), contentType).status shouldBe 415
      val response = client.url(s"$baseUrl/report-to/test-service")
        .post(Json.stringify(Json.arr(validReport))).futureValue
      response.status shouldBe 415
