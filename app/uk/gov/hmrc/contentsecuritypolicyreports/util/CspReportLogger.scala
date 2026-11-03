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

package uk.gov.hmrc.contentsecuritypolicyreports.util

import org.slf4j.MDC
import play.api.Logger

object CspReportLogger:
    
  private val logger = Logger("csp-report-logger")

  def info[A](mdc: Map[String, String])(message: => String): Unit =
    withMdc(mdc):
      logger.info(message)

  private def withMdc[A](mdc: Map[String, String])(block: => A): A =
    val oldMdcData = MDC.getCopyOfContextMap

    try
      mdc.foreach: (key, value) =>
        MDC.put(key, value)
      block
    finally
      if oldMdcData != null then
        MDC.setContextMap(oldMdcData)
      else
        MDC.clear()
