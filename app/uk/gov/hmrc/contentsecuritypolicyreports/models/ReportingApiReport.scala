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

import play.api.libs.json.{JsObject, Json, JsonValidationError, Reads, __}

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

case class ReportingApiReport(
  `type` : JsObject,
  body     : JsObject,
  age      : Option[Long],
  userAgent: Option[String]
)

object ReportingApiReport:
  given Reads[ReportingApiReport] = Reads: json =>
    for
      `type` <- json.validate[JsObject]
      _ <- (__ \ "type").read[String]
             .filter(JsonValidationError("Expected csp-violation"))(_ == "csp-violation")
             .reads(json)
      body <- (__ \ "body").read[JsObject].reads(json)
      _ <- body.validate[CspViolationReportBody]
      _ <- (__ \ "url").readNullable[String].reads(json)
      age <- (__ \ "age").readNullable[Long]
             .filter(JsonValidationError("Expected non-negative age"))(_.forall(_ >= 0))
             .reads(json)
      userAgent <- (__ \ "user_agent").readNullable[String].reads(json)
    yield ReportingApiReport(`type`, body, age, userAgent)