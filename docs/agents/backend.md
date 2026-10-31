# Backend

- Code is split into feature packages, each with `controller`, `service`, `repository` and `domain` layers. Controllers own HTTP and JSON. Services own workflows and transactions. Repositories own SQL. Domain code has no Spring MVC, Jackson or JDBC imports. The architecture tests enforce this; see [ADR 0001](../adr/0001-feature-packages-with-enforced-layers.md).
- Repositories use plain SQL through `JdbcTemplate`. There is no ORM.
- Backend tests go through HTTP against a real temporary SQLite database.
- Never edit, rename or delete a Flyway migration that is already on `main`; add the next version. SQL and Java migrations share one version sequence.
