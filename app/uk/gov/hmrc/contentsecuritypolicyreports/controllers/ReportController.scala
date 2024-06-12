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
import play.api.libs.json._
import play.api.mvc.{Action, MessagesControllerComponents}
import play.api.http.HeaderNames
import uk.gov.hmrc.contentsecuritypolicyreports.controllers.ReportController.ScalaCSPReport
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}

@Singleton
class ReportController @Inject()(
  mcc: MessagesControllerComponents,
) extends FrontendController(mcc):

  private val cspLogger = Logger("csp-report-logger")

  def report(service: String): Action[JsValue] =
    // using tolerantJson since CSP Reports have Content-Type: application/csp-report
    // we're not using CSPReportBodyParser as it has an issue with String/Long
    Action(parse.tolerantJson):
      implicit request =>
        val json = request.body
        // only accept and log the payload if it's a valid CSP Report
        json.validate[ScalaCSPReport] match
         case JsSuccess(_, _) =>
           val mdc =
             ("reporting-service" -> service) ::
               request
                  .headers
                  .get(HeaderNames.USER_AGENT)
                  .toList
                  .flatMap: userAgent =>
                    List("user-agent" -> userAgent)
           withMdc(mdc.toMap):
             cspLogger.info(Json.prettyPrint(json))
           Ok
         case JsError(errors) =>
           BadRequest(JsError.toJson(errors))

  private def withMdc[A](mdc: Map[String, String])(block: => A): A =
    val oldMdcData = MDC.getCopyOfContextMap
    try
      mdc.foreach:
        (k, v) => MDC.put(k, v)
      block
    finally
      if oldMdcData != null then
        MDC.setContextMap(oldMdcData)
      else
        MDC.clear()

object ReportController:
  // TODO: Remove once we are using Play 2.9
  // This ScalaCSPReport model has been copied from this PR: https://github.com/playframework/playframework/pull/10889
  // which has been merged and will be released as part of Play 2.9 - at which point we will remove this.
  // For more context, see issue: https://github.com/playframework/playframework/issues/10876
  final case class ScalaCSPReport(
    documentUri       : String,
    violatedDirective : String,
    blockedUri        : Option[String] = None,
    originalPolicy    : Option[String] = None,
    effectiveDirective: Option[String] = None,
    referrer          : Option[String] = None,
    disposition       : Option[String] = None,
    scriptSample      : Option[String] = None,
    statusCode        : Option[Int   ] = None,
    sourceFile        : Option[String] = None,
    lineNumber        : Option[Long  ] = None,
    columnNumber      : Option[Long  ] = None
  )

  object ScalaCSPReport:
    val longOrStringToLongRead: Reads[Long] =
      case JsString(s) =>
        try
          JsSuccess(s.toLong)
        catch
          case _: NumberFormatException => jsError("Could not parse line or column number in CSP Report; Inappropriate format")
      case JsNumber(s) =>
        JsSuccess(s.toLong)
      case _           =>
        jsError("Could not parse line or column number in CSP Report; Expected a number or a String")

    private def jsError(message: String) =
      JsError(Seq(JsPath -> Seq(JsonValidationError(message))))

    implicit val reads: Reads[ScalaCSPReport] =
      ( (__ \ "csp-report" \ "document-uri"       ).read[String]
      ~ (__ \ "csp-report" \ "violated-directive" ).read[String]
      ~ (__ \ "csp-report" \ "blocked-uri"        ).readNullable[String]
      ~ (__ \ "csp-report" \ "original-policy"    ).readNullable[String]
      ~ (__ \ "csp-report" \ "effective-directive").readNullable[String]
      ~ (__ \ "csp-report" \ "referrer"           ).readNullable[String]
      ~ (__ \ "csp-report" \ "disposition"        ).readNullable[String]
      ~ (__ \ "csp-report" \ "script-sample"      ).readNullable[String]
      ~ (__ \ "csp-report" \ "status-code"        ).readNullable[Int]
      ~ (__ \ "csp-report" \ "source-file"        ).readNullable[String]
      ~ (__ \ "csp-report" \ "line-number"        ).readNullable[Long](longOrStringToLongRead)
      ~ (__ \ "csp-report" \ "column-number"      ).readNullable[Long](longOrStringToLongRead)
      )(ScalaCSPReport.apply _)
