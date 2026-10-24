# Spring Security with Geobook's own session store

Login uses Spring Security so every endpoint is denied unless explicitly opened, including endpoints added later. Sessions are not Spring Session or JWTs: Geobook stores a SHA-256 hash of a random token in SQLite and looks it up on every request, so revoking a session or changing the password takes effect on the next request. Brute-force limits and public mode are Geobook's own code on top of the filter chain.
