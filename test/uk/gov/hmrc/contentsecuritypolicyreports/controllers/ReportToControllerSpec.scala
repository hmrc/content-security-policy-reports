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

  "POST /report-to/:service" should:
    "return 200 when receiving a Reporting API CSP batch" in:
      withLogs: appender =>
        postReports(Json.arr(validReport)).status shouldBe 200
        val events = appender.list.asScala.toList
        events should have size 1
        Json.parse(events.head.getFormattedMessage) shouldBe validReport
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
        Set("document-uri", "blocked-uri", "violated-directive", "effective-directive", "script-sample")
          .foreach(field => mdc should not contain key(field))

    "log each valid report with isolated fields across batches and requests" in:
      withLogs: appender =>
        val minimalReport = Json.obj(
          "type" -> "csp-violation",
          "body" -> Json.obj("documentURL" -> "https://example.com/other", "effectiveDirective" -> "style-src-elem")
        )
        postReports(Json.arr(validReport, minimalReport)).status shouldBe 200
        postReports(Json.arr(minimalReport), service = "other-service").status shouldBe 200
        val events = appender.list.asScala.toList
        events.map(event => Json.parse(event.getFormattedMessage)) shouldBe List(validReport, minimalReport, minimalReport)
        events.tail.foreach: event =>
          val mdc = event.getMDCPropertyMap.asScala.toMap
          mdc("user-agent") shouldBe "upload-agent"
          mdc("documentURL") shouldBe "https://example.com/other"
          mdc("age") shouldBe "-"
          mdc should not contain key("sample")
          mdc should not contain key("sourceFile")
        events.last.getMDCPropertyMap.get("reporting-service") shouldBe "other-service"

    "accept nullable fields and preserve unknown body fields without letting them override collector metadata" in:
      withLogs: appender =>
        val body = reportBody ++ Json.obj(
          "sourceFile" -> JsNull,
          "lineNumber" -> JsNull,
          "columnNumber" -> JsNull,
          "sample" -> JsNull,
          "disposition" -> "report",
          "futureField" -> Json.obj("detail" -> true),
          "reporting-service" -> "spoofed-service",
          "reporting-api" -> "spoofed-api",
          "user-agent" -> "spoofed-agent"
        )
        val report = validReport ++ Json.obj("body" -> body)
        postReports(Json.arr(report)).status shouldBe 200
        val event = appender.list.get(0)
        Json.parse(event.getFormattedMessage) shouldBe report
        val mdc = event.getMDCPropertyMap.asScala.toMap
        mdc("sourceFile") shouldBe "-"
        mdc("lineNumber") shouldBe "-"
        mdc("columnNumber") shouldBe "-"
        mdc("sample") shouldBe "-"
        mdc("disposition") shouldBe "report"
        mdc("futureField") shouldBe """{"detail":true}"""
        mdc("reporting-service") shouldBe "test-service"
        mdc("reporting-api") shouldBe "report-to"
        mdc("user-agent") shouldBe "original-page-agent"

    "log each envelope's age without accepting a body override or leaking it between reports" in:
      withLogs: appender =>
        val report = validReport ++ Json.obj("body" -> (reportBody ++ Json.obj("age" -> 999)))
        val reports = Seq(
          report ++ Json.obj("age" -> 0),
          report ++ Json.obj("age" -> 456),
          report - "age",
          report ++ Json.obj("age" -> JsNull)
        )
        postReports(Json.toJson(reports)).status shouldBe 200
        val events = appender.list.asScala.toList
        events.map(_.getMDCPropertyMap.get("age")) shouldBe List("0", "456", "-", "-")
        events.map(event => Json.parse(event.getFormattedMessage)) shouldBe reports.toList

    "return 400 with one error log and no violation logs regardless of invalid entry order" in:
      val invalidReports = Seq(
        validReport ++ Json.obj("type" -> "deprecation"),
        validReport ++ Json.obj("body" -> (reportBody - "documentURL")),
        validReport ++ Json.obj("age" -> -1),
        JsNull
      )
      invalidReports.foreach: invalid =>
        Seq(Json.arr(invalid, validReport), Json.arr(validReport, invalid), Json.arr(validReport, invalid, validReport)).foreach: batch =>
          withLogs: appender =>
            val response = postReports(batch)
            response.status shouldBe 400
            (response.json \ "statusCode").as[Int] shouldBe 400
            assertRejectionLog(appender)

    "accept an empty batch without logging" in:
      withLogs: appender =>
        postReports(Json.arr()).status shouldBe 200
        appender.list shouldBe empty

    "accept a charset parameter" in:
      postReports(Json.arr(validReport), "application/reports+json; charset=utf-8").status shouldBe 200

    "restore the previous MDC after logging and clear newly introduced fields when no context existed" in:
      val components = app.injector.instanceOf[MessagesControllerComponents]
      val executionContext = ExecutionContext.parasitic
      val synchronousComponents = DefaultMessagesControllerComponents(
        components.messagesActionBuilder,
        DefaultActionBuilder(components.actionBuilder.parser)(executionContext),
        components.parsers,
        components.messagesApi,
        components.langs,
        components.fileMimeTypes,
        executionContext
      )
      val controller = new ReportToController(synchronousComponents)
      val request = FakeRequest(POST, "/report-to/test-service").withBody(Json.arr(validReport))
      val oldContext = Option(MDC.getCopyOfContextMap)
      try
        withLogs: appender =>
          val seededContext = Map("sentinel" -> "original", "documentURL" -> "previous-document")
          MDC.setContextMap(seededContext.asJava)
          controller.reportTo("test-service")(request).futureValue.header.status shouldBe 200
          MDC.getCopyOfContextMap.asScala.toMap shouldBe seededContext
          appender.list.get(0).getMDCPropertyMap.get("documentURL") shouldBe "https://example.com/page"

          MDC.clear()
          controller.reportTo("test-service")(request).futureValue.header.status shouldBe 200
          Option(MDC.getCopyOfContextMap).map(_.asScala.toMap).getOrElse(Map.empty) shouldBe empty
      finally
        oldContext match
          case Some(context) => MDC.setContextMap(context)
          case None          => MDC.clear()

    "return 415 for unsupported or absent content types without logging" in:
      withLogs: appender =>
        Seq("application/json", "application/csp-report", "text/plain").foreach: contentType =>
          postReports(Json.arr(validReport), contentType).status shouldBe 415
        val response = client.url(s"$baseUrl/report-to/test-service")
          .post(Json.stringify(Json.arr(validReport))).futureValue
        response.status shouldBe 415
        appender.list shouldBe empty

    "return 400 for malformed JSON with one error log and no violation logs" in:
      withLogs: appender =>
        val response = client.url(s"$baseUrl/report-to/test-service")
          .withHttpHeaders(CONTENT_TYPE -> "application/reports+json")
          .post("[{not-json}]").futureValue
        response.status shouldBe 400
        assertRejectionLog(appender)

    "return 413 when the configured body limit is exceeded without logging" in:
      withLogs: appender =>
        val limit = app.configuration.underlying.getBytes("play.http.parser.maxMemoryBuffer")
        val oversizedReport = validReport ++ Json.obj("body" -> (reportBody ++ Json.obj("sample" -> ("x" * (limit.toInt + 1)))))
        postReports(Json.arr(oversizedReport)).status shouldBe 413
        appender.list shouldBe empty

    val invalidPayloads = Seq(
      "a single envelope instead of an array" -> validReport,
      "a scalar root" -> Json.toJson("invalid"),
      "a null root" -> JsNull,
      "a non-object entry" -> Json.arr(42),
      "a missing type" -> Json.arr(validReport - "type"),
      "a non-string type" -> Json.arr(validReport ++ Json.obj("type" -> 42)),
      "a non-CSP report" -> Json.arr(validReport ++ Json.obj("type" -> "deprecation")),
      "a missing body" -> Json.arr(validReport - "body"),
      "a null body" -> Json.arr(validReport ++ Json.obj("body" -> JsNull)),
      "an array body" -> Json.arr(validReport ++ Json.obj("body" -> Json.arr())),
      "a missing documentURL" -> Json.arr(validReport ++ Json.obj("body" -> (reportBody - "documentURL"))),
      "a missing effectiveDirective" -> Json.arr(validReport ++ Json.obj("body" -> (reportBody - "effectiveDirective"))),
      "a negative age" -> Json.arr(validReport ++ Json.obj("age" -> -1)),
      "a fractional age" -> Json.arr(validReport ++ Json.obj("age" -> 1.5)),
      "an invalid url" -> Json.arr(validReport ++ Json.obj("url" -> 42)),
      "an invalid user_agent" -> Json.arr(validReport ++ Json.obj("user_agent" -> 42)),
      "a legacy report" -> Json.arr(Json.obj("csp-report" -> Json.obj("document-uri" -> "https://example.com")))
    ) ++ Seq("documentURL", "effectiveDirective", "blockedURL", "originalPolicy", "referrer", "sample", "disposition", "sourceFile")
      .map(field => s"an invalid $field" -> Json.arr(validReport ++ Json.obj("body" -> (reportBody ++ Json.obj(field -> 42)))))
      ++ Seq("statusCode", "lineNumber", "columnNumber")
        .flatMap(field => Seq(Json.toJson("invalid"), Json.toJson(1.5), Json.toJson(BigDecimal("9223372036854775808")))
          .map(value => s"an invalid $field value $value" -> Json.arr(validReport ++ Json.obj("body" -> (reportBody ++ Json.obj(field -> value))))))

    invalidPayloads.foreach: (description, payload) =>
      s"return 400 for $description with one error log and no violation logs" in:
        withLogs: appender =>
          postReports(payload).status shouldBe 400
          assertRejectionLog(appender)

    "leave legacy logging unchanged after receiving Reporting API reports" in:
      withLogs: appender =>
        postReports(Json.arr(validReport)).status shouldBe 200
        val response = client.url(s"$baseUrl/test-service")
          .withHttpHeaders(CONTENT_TYPE -> "application/csp-report")
          .post("""{"csp-report":{"document-uri":"https://example.com","violated-directive":"script-src"}}""")
          .futureValue
        response.status shouldBe 200
        val events = appender.list.asScala.toList
        events should have size 2
        val legacyMdc = events.last.getMDCPropertyMap.asScala.toMap
        legacyMdc("document-uri") shouldBe "https://example.com"
        legacyMdc("reporting-api") shouldBe "report-uri"
        legacyMdc should not contain key("age")
        legacyMdc should not contain key("documentURL")
