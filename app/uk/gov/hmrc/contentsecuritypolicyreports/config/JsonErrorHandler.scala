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

package uk.gov.hmrc.contentsecuritypolicyreports.config

import play.api.Logger
import play.api.http.HttpErrorHandler
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.Results.Status
import play.api.mvc.{RequestHeader, Result}
import play.api.libs.json.Json.toJson

import javax.inject.Singleton
import scala.concurrent.Future

@Singleton
class JsonErrorHandler extends HttpErrorHandler {
  private val logger = Logger(getClass)

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    Future.successful(Status(statusCode)(toJson(ErrorResponse(statusCode, message))))

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    logger.error(s"! Internal server error, for (${request.method}) [${request.uri}] -> ", exception)
    val errorResponse = ErrorResponse(INTERNAL_SERVER_ERROR, "Unexpected error")

    Future.successful(Status(errorResponse.statusCode)(toJson(errorResponse)))
  }
}

case class ErrorResponse(
                          statusCode: Int,
                          message: String,
                          xStatusCode: Option[String] = None,
                          requested: Option[String]   = None
                        )

object ErrorResponse {
  implicit val format: OFormat[ErrorResponse] = Json.format[ErrorResponse]
}
