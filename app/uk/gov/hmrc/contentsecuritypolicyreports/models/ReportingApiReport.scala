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

package uk.gov.hmrc.contentsecuritypolicyreports.models

import play.api.libs.json.{JsObject, Json, JsonValidationError, Reads, Writes, __}

case class CspViolationReportBody(
  documentURL       : String,
  effectiveDirective: String,
  blockedURL        : Option[String],
  originalPolicy    : Option[String],
  referrer          : Option[String],
  sample            : Option[String],
  disposition       : Option[String],
  statusCode        : Option[Int],
  sourceFile        : Option[String],
  lineNumber        : Option[Long],
  columnNumber      : Option[Long]
)

object CspViolationReportBody:
  given Reads[CspViolationReportBody] = Json.reads[CspViolationReportBody]
  given Writes[CspViolationReportBody] = Json.writes[CspViolationReportBody]

// Note that we don't capture the "url" field from the Reporting API as, for CSP violation reports, it's identical to the "documentURL" in the report body
case class ReportingApiReport(
  age      : Long,
  userAgent: String,
  body     : CspViolationReportBody,
)

object ReportingApiReport:
  given Reads[ReportingApiReport] = Reads: json =>
    for
    // The reporting API could, in theory, include other types of reports - but we only care about and expect csp-violation reports
      _ <- (__ \ "type").read[String]
             .filter(JsonValidationError("Expected csp-violation"))(_ == "csp-violation")
             .reads(json)
      age <- (__ \ "age").read[Long].reads(json)
      userAgent <- (__ \ "user_agent").read[String].reads(json)
      body <- (__ \ "body").read[CspViolationReportBody].reads(json)
    yield ReportingApiReport(age, userAgent, body)

  given Writes[ReportingApiReport] = Json.writes[ReportingApiReport]
