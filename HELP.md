# Getting Started

## Build checks

Set `JAVA_HOME` to a JDK 27 installation and run `./mvnw verify` from the project root.
Maven installs the pinned Node and npm versions,
runs `npm ci`, checks and builds the frontend, and runs the backend tests and analysis.
The existing backend context test needs Docker for its PostgreSQL Testcontainer.
The root `.java-version` selects Java 27 for local Java version managers.
Maven Enforcer checks the JDK before any formatting, frontend work, or compilation.
Java versions outside 27 fail immediately with instructions to set `JAVA_HOME`.

`./mvnw package` also enforces all static checks. Spotless and Checkstyle run during
`validate`; PMD and SpotBugs run during `prepare-package`. They check both main and
test Java sources. SpotBugs includes Find Security Bugs and checks all bug categories.
PMD enables the same eight categories as Tergo, without its application-specific exceptions.
Its test-rule exceptions cover only the generated launcher, bean configuration, and context-startup test.

Run `./mvnw spotless:apply` to format Java with Google Java Format. Builds check formatting
and fail on violations; they do not rewrite source files.

From `src/main/frontend`, run `npm ci` and `npm run build`. The build runs Prettier,
ESLint with zero warnings, TypeScript, and Fallow before Next.js. Use `npm run check`
for analysis alone or `npm run format` to apply Prettier's formatting.
ESLint uses TypeScript's project service to reject unhandled promises, misused async callbacks,
and unsafe `any` operations. TypeScript also enables `noUncheckedIndexedAccess` and
`exactOptionalPropertyTypes`.

Fallow checks unused code and dependencies, import cycles, duplication, and complexity
across the whole frontend. It needs no Git history or baseline. Its only dependency
exception is `@tailwindcss/turbopack`, which Next.js loads by its string name in configuration.
Generated files, caches, and Maven's downloaded Node installation are excluded.

GitHub Actions runs these same builds on pushes and pull requests. Keep
`src/main/frontend/.nvmrc` and `node.version` in `pom.xml` synchronized.
Keep `@types/node` on that same Node major. Dependabot updates its minor and patch
versions; change its major when upgrading the runtime.

The relevant tool documentation is [Fallow CI](https://fallow.tools/docs/integrations/ci/),
[Checkstyle's build gate](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html),
and [SpotBugs configuration](https://spotbugs.github.io/spotbugs-maven-plugin/spotbugs-mojo.html).

Google Java Format is pinned to 1.36.1 because 1.37 fails through Spotless 3.10.4 on both
JDK 25 and JDK 27. ESLint 10 passes the frontend checks; npm still reports older peer ranges
from some Next.js lint plugins. npm also reports the upstream
[braces advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) in Next.js's lint dependencies.
The registry currently offers no newer braces release, so no forced downgrade or dependency override is applied.

## Security checks

Run a local secret scan from the project root, even before Git initialization:

```bash
docker run --rm --user "$(id -u):$(id -g)" --volume "${PWD}:/repo:ro" \
  ghcr.io/gitleaks/gitleaks:v8.30.1 dir --redact --config /repo/.gitleaks.toml /repo
```

Gitleaks uses its default detection rules and excludes generated output and downloaded dependencies.
CI scans the full checked-out Git history, including secrets removed in later commits.
Reports redact secrets. A finding fails the security job.

GitHub Dependency Review runs on pull requests and rejects newly introduced high or critical
vulnerabilities in runtime, development, and unknown dependency scopes. It requires the dependency
graph to be enabled. Existing vulnerabilities still need maintenance through Dependabot alerts.

## GitHub repository settings

These settings are prepared for the future public repository. Files alone do not activate them.
After the first push and CI run, enable the dependency graph and Dependabot alerts under
Settings > Advanced Security. Replace `OWNER/REPO` below with the repository name:

```bash
gh api --method PATCH repos/OWNER/REPO --input .github/security-settings.json
gh api --method POST repos/OWNER/REPO/rulesets --input .github/required-checks.json
```

The first command enables native secret scanning and push protection. The second requires
`Backend checks`, `Frontend checks`, `Secret scan`, and `Dependency review` on the default branch,
with the branch up to date before merging. It accepts checks only from GitHub Actions and grants
no bypasses. The ruleset can also be imported through Settings > Rules > Rulesets.
Create it once; subsequent changes should update the existing ruleset rather than create duplicates.

See [typed ESLint](https://typescript-eslint.io/getting-started/typed-linting/),
[Maven Enforcer](https://maven.apache.org/enforcer/enforcer-rules/requireJavaVersion.html),
[Gitleaks](https://github.com/gitleaks/gitleaks),
[Dependency Review](https://github.com/actions/dependency-review-action), and
[GitHub rulesets](https://docs.github.com/en/rest/repos/rules#create-a-repository-ruleset).

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)
* [Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/4.1.1/reference/testing/testcontainers.html#testing.testcontainers)
* [Testcontainers Postgres Module Reference Guide](https://java.testcontainers.org/modules/databases/postgres/)
* [Spring Web](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html)
* [HTTP Client](https://docs.spring.io/spring-boot/4.1.1/reference/io/rest-client.html#io.rest-client.restclient)
* [Spring Security](https://docs.spring.io/spring-boot/4.1.1/reference/web/spring-security.html)
* [Spring Session for JDBC](https://docs.spring.io/spring-session/reference/)
* [Flyway Migration](https://docs.spring.io/spring-boot/4.1.1/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway)
* [Testcontainers](https://java.testcontainers.org/)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Securing a Web Application](https://spring.io/guides/gs/securing-web/)
* [Spring Boot and OAuth2](https://spring.io/guides/tutorials/spring-boot-oauth2/)
* [Authenticating a User with LDAP](https://spring.io/guides/gs/authenticating-ldap/)

### Testcontainers support

This project uses [Testcontainers at development time](https://docs.spring.io/spring-boot/4.1.1/reference/features/dev-services.html#features.dev-services.testcontainers).

Testcontainers has been configured to use the following Docker images:

* [`postgres:latest`](https://hub.docker.com/_/postgres)

Please review the tags of the used images and set them to the same as you're running in production.

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.
