
# content-security-policy-reports

PlatOps owned service to capture content-security-policy violation reports platform wide.

MDTP services inherit basic CSP config when they use bootstrap-play. This config instructs browsers to send a report if the CSP is violated. Reports for all MDTP services are sent to this service.

### Endpoints

All endpoints are exempt from CSRF checks.

| Endpoint | CSP directive | Content-Type | Payload | Description |
| --- | --- | --- | --- | --- |
| `POST /:service` | `report-uri` | `application/csp-report` | A single legacy `csp-report` object | deprecated endpoint which is being phased out |
| `POST /report-to/:service` | `report-to` | `application/reports+json` | An array of Reporting API envelopes | Replacement endpoint for modern CSP reporting |
| `OPTIONS /report-to/:service` | `report-to` | N/A | N/A | Supports preflight requests from browsers |


Example report-uri request:

```http
POST /content-security-policy-reports/example-service
Content-Type: application/csp-report

{
  "csp-report": {
    "document-uri": "http://localhost:9017/",
    "violated-directive": "script-src",
    "blocked-uri": "eval",
    "original-policy": "script-src 'nonce-WJKk/rf6CetW28Omwee+Ww==' 'unsafe-inline' 'strict-dynamic' https: http: 'wasm-unsafe-eval'; object-src 'none'; base-uri 'none'; report-uri http://localhost:8861/content-security-policy-reports/test",
    "effective-directive": "script-src",
    "referrer": "",
    "disposition": "enforce",
    "status-code": 200,
    "source-file": "debugger eager eval code",
    "line-number": 1,
    "column-number": 1
  }
}
```

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

### Tests

```sh
sbt 'testOnly uk.gov.hmrc.contentsecuritypolicyreports.controllers.ReportControllerSpec'
sbt test
sbt 'it/test'
```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").