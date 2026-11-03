
# content-security-policy-reports

PlatOps owned service to capture content-security-policy reports platform wide.

### Endpoints

Both endpoints are under `/content-security-policy-reports` and are exempt from CSRF checks.

| Endpoint | CSP directive | Content-Type | Payload |
| --- | --- | --- | --- |
| `POST /:service` | `report-uri` | `application/csp-report` | A single legacy `csp-report` object |
| `POST /report-to/:service` | `report-to` | `application/reports+json` | An array of Reporting API envelopes |

Example Reporting API request:

```http
POST /content-security-policy-reports/report-to/example-service
Content-Type: application/reports+json

[
	{
		"age": 123,
		"type": "csp-violation",
		"url": "https://example.com/page",
		"user_agent": "Mozilla/5.0",
		"body": {
			"documentURL": "https://example.com/page",
			"effectiveDirective": "script-src-elem",
			"blockedURL": "inline",
			"originalPolicy": "default-src 'self'; report-to csp-endpoint",
			"disposition": "enforce",
			"statusCode": 200,
			"sample": "",
			"sourceFile": null,
			"lineNumber": null,
			"columnNumber": null
		}
	}
]
```

The new endpoint requires `type: "csp-violation"`, an object `body`, and string
`documentURL` and `effectiveDirective` body fields. Other known CSP body fields
may be absent or null, but must have the correct type when supplied. Unknown body
fields are retained. Envelope `age`, `url`, and `user_agent` are optional; when
supplied they must be a non-negative integer, string, and string respectively.

The entire batch is validated before any reports are logged. The endpoint
returns `200` for an entirely valid batch (including an empty array), or `400`
if any entry is invalid or is not a CSP report. Rejected batches are not
processed and produce no violation logs. Malformed JSON or a non-array root
also returns `400` without logging violations. Each `400` rejection emits one
ERROR to `csp-report-logger` with service and protocol context, without logging
the request payload. Unsupported media types return `415`; requests exceeding
Play's configured JSON body-size limit return `413`.

Each valid entry is logged to `csp-report-logger` as its original JSON envelope,
preserving the body keys. New endpoint MDC fields use those same keys, such as
`documentURL`, `blockedURL`, and `sample`, without legacy aliases. MDC string
values are unchanged, other values are JSON-encoded, null is represented by `-`,
and absent fields are omitted. Collector metadata adds `reporting-service` from
the route and `reporting-api=report-to`. The `user-agent` metadata uses the
envelope's `user_agent`, falling back to the HTTP header or `-`. Body fields
cannot override these collector metadata values. Each report's envelope `age`
is also included in MDC and the log output, in milliseconds, or `-` when absent
or null; a body field named `age` cannot override it. Legacy report payloads and
MDC field names remain unchanged.

### Gradual Migration

Configure a named endpoint using the `Reporting-Endpoints` response header and
reference that name in the CSP `report-to` directive. Keep `report-uri` for
browsers that do not support the new directive:

```http
Reporting-Endpoints: csp-endpoint="https://reports.example.com/content-security-policy-reports/report-to/example-service"
Content-Security-Policy: default-src 'self'; report-uri https://reports.example.com/content-security-policy-reports/example-service; report-to csp-endpoint
```

Use the equivalent directives in `Content-Security-Policy-Report-Only` when
appropriate. Browsers that support `report-to` generally ignore `report-uri`
rather than delivering to both endpoints. Reporting API delivery is asynchronous
and best-effort; request counts are not violation counts because one request can
contain multiple reports. The separate routes distinguish HTTP usage, while the
`reporting-api` log marker identifies new-protocol violations.

Before rollout, trigger a real CSP violation in staging and verify delivery and
logs. Endpoints must use HTTPS. For cross-origin collectors, verify that the
gateway handles Reporting API CORS/preflight requests; this service does not add
a new CORS configuration.

### Tests

```sh
sbt 'testOnly uk.gov.hmrc.contentsecuritypolicyreports.controllers.ReportControllerSpec'
sbt test
sbt 'it/test'
```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").