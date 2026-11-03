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

import org.slf4j.MDC
import play.api.Logger
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsNull, JsString, JsSuccess, JsValue, Json, Writes, __}
import play.api.mvc.{Action, AnyContent, BodyParser, MessagesControllerComponents, RequestHeader}
import play.api.http.HeaderNames
import play.filters.csp.{CSPReportActionBuilder, ScalaCSPReport}
import uk.gov.hmrc.contentsecuritypolicyreports.config.ErrorResponse
import uk.gov.hmrc.contentsecuritypolicyreports.models.ReportingApiReport
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class ReportController @Inject()(
  mcc             : MessagesControllerComponents
, cspReportAction: CSPReportActionBuilder
) extends FrontendController(mcc):

  private val cspLogger = Logger("csp-report-logger")

  def report(service: String): Action[ScalaCSPReport] =
    cspReportAction:
      implicit request =>
        val mdc =
          Map("reporting-service" -> service, "reporting-api" -> "report-uri")
            ++ toMap(request.body)
            ++ request.headers.get(HeaderNames.USER_AGENT)
                 .toList
                 .flatMap: userAgent =>
                   List("user-agent" -> userAgent)
                 .toMap

        withMdc(mdc):
          cspLogger.info(Json.prettyPrint(Json.toJson(request.body)))

        Ok

  def reportToCORSPreFlight(service: String): Action[AnyContent] =
    Action: request =>
      NoContent.withHeaders(
        HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN -> "*",
        HeaderNames.ACCESS_CONTROL_ALLOW_METHODS -> "POST, OPTIONS",
        HeaderNames.ACCESS_CONTROL_ALLOW_HEADERS -> "Content-Type"
      )

  def reportTo(service: String): Action[JsValue] =
    def logRejection(request: RequestHeader): Unit =
      val mdc = Map(
        "reporting-service" -> service,
        "reporting-api" -> "report-to",
        "user-agent" -> request.headers.get(HeaderNames.USER_AGENT).getOrElse("-")
      )
      withMdc(mdc):
        cspLogger.error("Rejected CSP Reporting API request: expected a JSON array of valid csp-violation reports")

    val jsonParser = parse.when(
      _.contentType.contains("application/reports+json"),
      parse.tolerantJson,
      _ => Future.successful(UnsupportedMediaType(Json.toJson(ErrorResponse(415, "Expected application/reports+json"))))
    )
    val parser: BodyParser[JsValue] = BodyParser: request =>
      jsonParser(request).map {
        case Left(result) if result.header.status == BAD_REQUEST =>
          logRejection(request)
          Left(result)
        case result => result
      }(mcc.executionContext)

    Action(parser): request =>
      request.body.validate[Seq[ReportingApiReport]] match
        case JsSuccess(reports, _) =>
          reports.foreach: report =>
            val bodyMdc = report.body.fields.map:
              case (key, JsString(value)) => key -> value
              case (key, JsNull)          => key -> "-"
              case (key, value)           => key -> Json.stringify(value)
            val userAgent = report.userAgent.orElse(request.headers.get(HeaderNames.USER_AGENT))
            val mdc = bodyMdc.toMap
              ++ Map("reporting-service" -> service, "reporting-api" -> "report-to")
              ++ Map("user-agent" -> userAgent.getOrElse("-"), "age" -> report.age.map(_.toString).getOrElse("-"))

            withMdc(mdc):
              cspLogger.info(Json.prettyPrint(report.original))

          Ok
        case _ =>
          logRejection(request)
          BadRequest(Json.toJson(ErrorResponse(400, "Expected an array of valid CSP reports")))

  private def toMap(cspReport: ScalaCSPReport): Map[String, String] =
    def opt: Option[String] => String = _.getOrElse("-")
    Map(
      "document-uri"        ->     cspReport.documentUri
    , "violated-directive"  ->     cspReport.violatedDirective
    , "blocked-uri"         -> opt(cspReport.blockedUri)
    , "original-policy"     -> opt(cspReport.originalPolicy)
    , "effective-directive" -> opt(cspReport.effectiveDirective)
    , "referrer"            -> opt(cspReport.referrer)
    , "disposition"         -> opt(cspReport.disposition)
    , "script-sample"       -> opt(cspReport.scriptSample)
    , "status-code"         -> opt(cspReport.statusCode.map(_.toString))
    , "source-file"         -> opt(cspReport.sourceFile)
    , "line-number"         -> opt(cspReport.lineNumber.map(_.toString))
    , "column-number"       -> opt(cspReport.columnNumber.map(_.toString))
    )

  private def withMdc[A](mdc: Map[String, String])(block: => A): A =
    val oldMdcData = MDC.getCopyOfContextMap
    try
      mdc.foreach: (k, v) =>
        MDC.put(k, v)
      block
    finally
      if oldMdcData != null then
        MDC.setContextMap(oldMdcData)
      else
        MDC.clear()

  given Writes[ScalaCSPReport] =
    ( (__ \ "csp-report" \ "document-uri"       ).write[String]
    ~ (__ \ "csp-report" \ "violated-directive" ).write[String]
    ~ (__ \ "csp-report" \ "blocked-uri"        ).writeNullable[String]
    ~ (__ \ "csp-report" \ "original-policy"    ).writeNullable[String]
    ~ (__ \ "csp-report" \ "effective-directive").writeNullable[String]
    ~ (__ \ "csp-report" \ "referrer"           ).writeNullable[String]
    ~ (__ \ "csp-report" \ "disposition"        ).writeNullable[String]
    ~ (__ \ "csp-report" \ "script-sample"      ).writeNullable[String]
    ~ (__ \ "csp-report" \ "status-code"        ).writeNullable[Int]
    ~ (__ \ "csp-report" \ "source-file"        ).writeNullable[String]
    ~ (__ \ "csp-report" \ "line-number"        ).writeNullable[Long]
    ~ (__ \ "csp-report" \ "column-number"      ).writeNullable[Long]
    )(pt => Tuple.fromProductTyped((pt)))
