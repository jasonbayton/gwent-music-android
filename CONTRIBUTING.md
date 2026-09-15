# Contributing

## Local checks

Use the mock build for development and tests. Follow the [README](README.md) to
configure JDK 21, Android SDK 37 and the checked-in Gradle wrapper.

```sh
bash scripts/check.sh
git diff --check
```

The same command runs in CI and needs Python 3 and Go 1.26 as well as the Android
toolchain. It forces mock debug and keyless builds even with local live properties.
It also runs the mock server's Go race tests and vet. For focused iteration use
`./gradlew :app:testDebugUnitTest --tests '*YourTest'`, then run the full check before review.
Coverage reports and reviewed floors are described in [TESTING.md](TESTING.md).
These checks do not replace device validation for navigation, permissions,
process recreation or background execution.

## Change design

- Keep changes focused and link the issue and observed failure scenario.
- Keep Compose rendering separate from network and scheduling side effects.
- Expose immutable ViewModel state; collect it with lifecycle awareness.
- Inject dependencies at side-effect boundaries so tests can use fakes. Prefer
  a small testable contract over adding layers that have no consumers.
- Preserve coroutine cancellation. Do not convert cancellation into an ordinary
  empty result or retry. Represent API failure separately from successful empty data.
- Use durable state for work that must survive process death. Do not assume a
  WorkManager job runs at its requested wall-clock time.
- Document why a workaround exists, its constraints and its removal condition.
  Comments must describe actual behaviour, not an aspirational guarantee.
- Use Kotlin's official style. Use hyphens when a dash is appropriate, never en or
  em dashes in new prose, comments or issue text.

## Commentary standard

Use KDoc for non-obvious component contracts: state ownership, lifecycle, concurrency,
failure/retry behaviour and caller obligations. Put local explanations beside the decision
they protect. Explain why a tempting simplification would break an invariant, rather than
paraphrasing the next statement. Small self-explanatory helpers do not need ceremonial KDoc.

When changing behaviour, update its explanation in the same patch. Distinguish a local
guard from an end-to-end guarantee, and a tested contract from an assumption about a
remote service. Link the relevant regression test or helper when that aids maintenance.
Keep credentials, account data and private provider findings out of comments and examples.

## Tests expected with fixes

Write a regression test that fails for the original defect. Use synthetic data.
Prefer unit tests for parsing/planning, local Android tests for preferences and
WorkManager wiring, and device tests for activity intents and permissions.

Cover failure and recovery: failed HTTP-200 envelopes, token refresh failure,
quiet-hours transitions, midnight, stale queued work, sign-out/re-login and process
recreation. Test the production path, not a reimplementation of its logic.

Do not assert exact background delivery times. Do not use production credentials,
send real messages, or upload account data as build artefacts. Document which
scenarios were not exercised.

## Review and publication

Changes should receive independent review before merge. Describe test evidence
and remaining risks in the pull request. Do not close an issue merely because a
local patch exists. Publication requires maintainer approval and a reviewed,
keyless source snapshot. Internal planning and security discussions are not public
contribution material; use the private reporting route in [SECURITY.md](SECURITY.md).
