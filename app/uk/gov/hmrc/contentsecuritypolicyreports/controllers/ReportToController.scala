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

import play.api.Logging
import play.api.http.HeaderNames
import play.api.libs.json.{JsArray, JsError, JsObject, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, BodyParser, MessagesControllerComponents}
import uk.gov.hmrc.contentsecuritypolicyreports.config.ErrorResponse
import uk.gov.hmrc.contentsecuritypolicyreports.models.ReportingApiReport
import uk.gov.hmrc.contentsecuritypolicyreports.util.CspReportLogger
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class ReportToController @Inject()(
  mcc             : MessagesControllerComponents
) extends FrontendController(mcc) with Logging:

  def reportToCORSPreFlight(service: String): Action[AnyContent] =
    Action: request =>
      NoContent.withHeaders(
        HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN  -> "*",
        HeaderNames.ACCESS_CONTROL_ALLOW_METHODS -> "POST, OPTIONS",
        HeaderNames.ACCESS_CONTROL_ALLOW_HEADERS -> "Content-Type"
      )

  private def toMap(report: ReportingApiReport): Map[String, String] =
    def opt: Option[String] => String = _.getOrElse("-")
    // We don't log `url` as, for CSP violations, this is an identical value to documentURL
    Map(
        "age"                    -> opt(report.age.map(_.toString)),
        "user-agent"             -> opt(report.userAgent),
        "documentURL"            -> report.body.documentURL,
        "effectiveDirective"     -> report.body.effectiveDirective,
        "blockedURL"             -> opt(report.body.blockedURL),
        "originalPolicy"         -> opt(report.body.originalPolicy),
        "referrer"               -> opt(report.body.referrer),
        "sample"                 -> opt(report.body.sample),
        "disposition"            -> opt(report.body.disposition),
        "statusCode"             -> opt(report.body.statusCode.map(_.toString)),
        "sourceFile"             -> opt(report.body.sourceFile),
        "lineNumber"             -> opt(report.body.lineNumber.map(_.toString)),
        "columnNumber"           -> opt(report.body.columnNumber.map(_.toString))
        )

  def reportTo(service: String): Action[JsValue] =
    // Default parser will not accept "application/reports+json" content type, so we define a custom parser for it.
    // Spec states that only content type application/reports+json should be used but Safari uses application/csp-report, so we accept that too.
    val jsonParser = parse.when(
      _.contentType.exists(ct => ct == "application/reports+json" || ct == "application/csp-report"),
      parse.tolerantJson,
      _ => Future.successful(UnsupportedMediaType(Json.toJson(ErrorResponse(415, "Expected application/reports+json"))))
    )
    val parser: BodyParser[JsValue] = BodyParser: request =>
      jsonParser(request).map {
        case Left(result) if result.header.status == BAD_REQUEST =>
          logger.error(s"Rejected CSP Report-to API request from service $service: malformed JSON")
          Left(result)
        case result => result
      }(mcc.executionContext)

    Action(parser): request =>
      // We expect an array of reports, even if there's just one, but Safari always sends a single object so we account for both.
      val reports = request.body match
        case _: JsObject => request.body.validate[ReportingApiReport].map(Seq(_))
        case _           => request.body.validate[Seq[ReportingApiReport]]

      reports match
        case JsSuccess(reports, _) =>
          reports.foreach: report =>
            val mdc = toMap(report) ++
              Map("reporting-service" -> service, "reporting-api" -> "report-to")

            CspReportLogger.info(mdc)(Json.prettyPrint(Json.toJson(report)))

          Ok
        case error: JsError =>
          val validationDetails = Json.stringify(JsError.toJson(error))
          logger.error(s"Rejected CSP Report-to API request from service $service: invalid report format: $validationDetails")
          BadRequest(Json.toJson(ErrorResponse(400, "Expected a valid CSP report or an array of valid CSP reports")))
