# GMC mock API

Seeded stand-in for the Paritor Xperios API - the endpoints the app calls, in the same
envelope shape. No auth required (it ignores the Bearer/ApiKey headers), so it doubles as
the target for the debug build and for instrumented tests.

```bash
cd mockserver && go run .        # listens on :8099
```

- Android debug build already points at `http://10.0.2.2:8099/` and shows a
  "Use seeded mock data" button on the login screen (no OAuth needed).
- Diary is generated live: 13 weekly Thursday brass lessons, so "next lesson" is always ahead.
