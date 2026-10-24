# One owner signs in with a session cookie

Geobook copies PhotoPrism's login model so self-hosters get what they expect, with two deliberate differences. Each instance has exactly one owner and one collection: there are no roles or other users, because PhotoPrism's guest and visitor roles only make sense once there is someone to share with, and per-user collections would put an owner on every track. The browser carries the session in an HttpOnly, SameSite=Strict cookie instead of PhotoPrism's localStorage token and `X-Auth-Token` header, because Geobook renders uploaded file and track names and a script injected through one could read a localStorage token. Programs other than the browser use app passwords sent as `Authorization: Bearer`.

## Considered options

- PhotoPrism's localStorage token. Rejected because any XSS could steal a two-week session.
- Several users sharing one collection with roles. Deferred until sharing is a real need; the session store can gain a role later.
