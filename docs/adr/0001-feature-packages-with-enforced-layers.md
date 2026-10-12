# Feature packages with enforced layers

Organize Geobook's Java code by feature, starting with one `tracks` feature for tracks, imports, and original exports because they share persistence and lifecycle rules. Each feature contains the `controller`, `service`, `repository`, and `domain` packages it needs. Architecture checks must fail the build when the dependency rules are broken.

Controllers own HTTP and JSON handling, services coordinate workflows and transactions, repositories own SQL, and domain code owns values and rules without Spring MVC, Jackson, or JDBC dependencies. The extra response records and mappings are an accepted cost of separating these responsibilities.

Use ArchUnit for layer dependencies and package placement, and Spring Modulith for feature boundaries and dependency cycles. Add both now, even with only one feature, so rapid agent development cannot introduce unchecked dependencies as features appear. Run the checks as tests in the normal Maven build and CI.

Controllers may depend on services and domain code. Services may depend on repositories and domain code. Repositories may depend on domain code. Reverse dependencies are forbidden. Infrastructure exceptions for application startup, datasource configuration, and Flyway migrations must be explicit and narrow; classes outside the feature layers must not escape verification by default.

The refactor must preserve HTTP contracts, stored data, import behavior, and database upgrade compatibility.
