# TaxData 6.6.2 — Agent installer HTTP 500 fix

`/agent/setup.cmd` previously generated its Windows CMD body with Java `String.formatted(...)`.
The body contains `%TEMP%`, `%RANDOM%`, `%ERRORLEVEL%` and `%PSFILE%`; Java Formatter interpreted these percent sequences as format conversions and the endpoint could fail with HTTP 500.

6.6.2 uses a literal `__TaxData_BASE__` token and `String.replace(...)`, so Windows environment-variable syntax is preserved exactly.
