# Release notes

## Unreleased

### Owner login

Geobook now asks the owner to sign in. See [authentication](deployment.md#authentication).

**Before you upgrade**, add one of these to `.env`, then recreate the container:

- `GEOBOOK_OWNER_PASSWORD` with the password you want, 8 characters to 72 bytes,
  or a bcrypt hash in single quotes.
- `GEOBOOK_AUTH_MODE=public` to keep Geobook open as before. Do this only on a
  network you trust.

Without either, the new release refuses to start and its log names
`GEOBOOK_OWNER_PASSWORD`. Your tracks are not touched. Set the variable and start
again. Use the new `compose.yaml` as well, because Compose only passes the options
it lists, and copy the new lines from `.env.example`.
