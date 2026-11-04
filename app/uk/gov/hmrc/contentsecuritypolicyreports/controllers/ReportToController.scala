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
class ReportToController @Inject()(
  mcc             : MessagesControllerComponents
) extends FrontendController(mcc):

  private val cspLogger = Logger("csp-report-logger")

  //TODO: this method is repeated
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

  def reportToCORSPreFlight(service: String): Action[AnyContent] =
    Action: request =>
      NoContent.withHeaders(
        HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN -> "*",
        HeaderNames.ACCESS_CONTROL_ALLOW_METHODS -> "POST, OPTIONS",
        HeaderNames.ACCESS_CONTROL_ALLOW_HEADERS -> "Content-Type"
      )

  def reportTo(service: String): Action[JsValue] =
    val jsonParser = parse.when(
      _.contentType.contains("application/reports+json"),
      parse.tolerantJson,
      _ => Future.successful(UnsupportedMediaType(Json.toJson(ErrorResponse(415, "Expected application/reports+json"))))
    )
    val parser: BodyParser[JsValue] = BodyParser: request =>
      jsonParser(request).map {
        case Left(result) if result.header.status == BAD_REQUEST =>
          cspLogger.error(s"Rejected CSP Report-to API request from service $service: expected a JSON array of valid csp-violation reports")
          Left(result)
        case result => result
      }(mcc.executionContext)
      //TODO: is mcc.executionContext still required here if we're not doing any mdc logging anymore?

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
              cspLogger.info(Json.prettyPrint(report.`type`))

          Ok
        case _ =>
          cspLogger.error(s"Rejected CSP Report-to API request from service $service: expected a JSON array of valid csp-violation reports")
          BadRequest(Json.toJson(ErrorResponse(400, "Expected an array of valid CSP reports")))

