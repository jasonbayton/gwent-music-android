# Security policy

## Reporting a vulnerability

Email **project-support@bayton.org** privately. Do not report security vulnerabilities through public issues or pull requests.

Include the affected version or commit, impact, and minimal reproduction steps using synthetic data or your own authorised test account. Do not send passwords, tokens, API keys, HAR files, message content or other families' information.

## Scope and support

This policy covers the companion's Android source, build configuration and bundled mock server. The project is published as an archived reference; no production-supported version is declared, and no live build is distributed.

Provider-side concerns should be coordinated privately with the relevant provider. Do not probe accounts or records you are not authorised to access.

## Security expectations

Changes must preserve account isolation, fail closed when authentication is unavailable, protect stored credentials, avoid sensitive logging, and provide safe write and deletion behaviour. These are requirements, not a claim that every release gate has passed.

Passing tests or publishing source does not authorise distribution of a credential-bearing build. Release builds embed a key only when one is supplied locally and use debug signing, so they are for personal local use and not for distribution.
