# Release notes

## Unreleased

- Deployment instructions now cover replacing the release's `compose.yaml` and
  keeping existing `.env` settings during an update.
- Release images report their own version in the startup log instead of
  `0.0.1-SNAPSHOT`.

## 0.0.1, 25 October 2026

Published as a prerelease. Includes GPX import and playback, Google Takeout
history days, country review and original-export downloads.

### Owner login

Geobook now asks the owner to sign in. See [authentication](deployment.md#authentication).
Scripts that upload or read tracks need an [app password](deployment.md#app-passwords)
from the account page.

**Before you upgrade**, add one of these to `.env`, then recreate the container:

- `GEOBOOK_OWNER_PASSWORD` with the password you want, 8 characters to 72 bytes,
  or a bcrypt hash in single quotes.
- `GEOBOOK_AUTH_MODE=public` to keep Geobook open as before. Do this only on a
  network you trust.

Without either, the new release refuses to start and its log names
`GEOBOOK_OWNER_PASSWORD`. Your tracks are not touched. Set the variable and start
again. Use the new `compose.yaml` as well, because Compose only passes the options
it lists, and copy the new lines from `.env.example`.
