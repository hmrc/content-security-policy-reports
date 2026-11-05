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

import play.api.http.HeaderNames
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{Json, Writes, __}
import play.api.mvc.{Action, MessagesControllerComponents}
import play.filters.csp.{CSPReportActionBuilder, ScalaCSPReport}
import uk.gov.hmrc.contentsecuritypolicyreports.util.CspReportLogger
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}

@Singleton
class ReportController @Inject()(
  mcc             : MessagesControllerComponents
, cspReportAction: CSPReportActionBuilder
) extends FrontendController(mcc):

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

        CspReportLogger.info(mdc)(Json.prettyPrint(Json.toJson(request.body)))
        
        Ok

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
