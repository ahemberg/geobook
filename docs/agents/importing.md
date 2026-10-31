# Importing external data

Applies to code that parses an uploaded GPX or Takeout file, or consumes the import event stream.

- Follow [review standards: importing external data](review-standards.md#importing-external-data). Synthetic tests are small and clean, so passing tests do not prove those rules hold.
- Before finishing, run `scripts/takeout-smoke.sh`. It imports a real Takeout into a throwaway instance. If it skips because the archive is missing, say so.
