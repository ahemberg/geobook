package se.geobook.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

// Rules use literal package names; each test verifies one architectural constraint.
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestContainsTooManyAsserts"})
class ArchitectureTests {
  // ArchUnit rules require JavaClasses, not its Iterable interface.
  @SuppressWarnings("PMD.LooseCoupling")
  private static final JavaClasses APPLICATION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importUrl(TrackerApplication.class.getProtectionDomain().getCodeSource().getLocation());

  @Test
  void productionClassesBelongToFeatureLayersOrExplicitInfrastructure() {
    classes()
        .that()
        .doNotHaveFullyQualifiedName("se.geobook.tracker.TrackerApplication")
        .and()
        .doNotHaveFullyQualifiedName("se.geobook.tracker.configuration.StorageConfiguration")
        .and()
        .doNotHaveFullyQualifiedName("se.geobook.tracker.configuration.ClockConfiguration")
        .and()
        .resideOutsideOfPackage("db.migration..")
        // The command line entry point is an input adapter beside the HTTP controllers.
        .and()
        .resideOutsideOfPackage("se.geobook.tracker.cli..")
        .should()
        .resideInAnyPackage(
            "se.geobook.tracker.*.controller..",
            "se.geobook.tracker.*.service..",
            "se.geobook.tracker.*.repository..",
            "se.geobook.tracker.*.domain..")
        .check(APPLICATION);
  }

  @Test
  void dependenciesFollowTheLayerDirection() {
    layeredArchitecture()
        .consideringAllDependencies()
        .layer("Controller")
        .definedBy("se.geobook.tracker.*.controller..")
        .layer("Service")
        .definedBy("se.geobook.tracker.*.service..")
        .layer("Repository")
        .definedBy("se.geobook.tracker.*.repository..")
        .layer("Domain")
        .definedBy("se.geobook.tracker.*.domain..")
        .optionalLayer("CommandLine")
        .definedBy("se.geobook.tracker.cli..")
        .whereLayer("Controller")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("CommandLine")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Service")
        .mayOnlyBeAccessedByLayers("Controller", "CommandLine")
        .whereLayer("Repository")
        .mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Domain")
        .mayOnlyBeAccessedByLayers("Controller", "Service", "Repository")
        .ignoreDependency(
            JavaClass.Predicates.resideInAPackage("db.migration.."),
            JavaClass.Predicates.resideInAPackage("se.geobook.tracker.tracks.domain.."))
        // main() hands subcommands to the command line before the server starts.
        .ignoreDependency(
            JavaClass.Predicates.equivalentTo(TrackerApplication.class),
            JavaClass.Predicates.resideInAPackage("se.geobook.tracker.cli.."))
        .check(APPLICATION);
  }

  @Test
  void domainDoesNotDependOnFrameworksOrPersistence() {
    noClasses()
        .that()
        .resideInAPackage("se.geobook.tracker.*.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "com.fasterxml.jackson..",
            "tools.jackson..",
            "java.sql..",
            "javax.sql..",
            "jakarta.persistence..",
            "jakarta.servlet..",
            "org.sqlite..",
            "com.zaxxer.hikari..",
            "org.flywaydb..")
        .check(APPLICATION);
  }

  @Test
  void httpAndResponseJsonBelongToControllers() {
    noClasses()
        .that()
        .resideOutsideOfPackage("se.geobook.tracker.*.controller..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..",
            "jakarta.servlet..",
            "com.fasterxml.jackson..",
            "tools.jackson.databind..")
        .check(APPLICATION);
    noClasses()
        .that()
        .resideOutsideOfPackage("se.geobook.tracker.*.controller..")
        // Only Takeout source parsing may use Jackson's streaming parser outside controllers.
        .and()
        .doNotHaveFullyQualifiedName("se.geobook.tracker.tracks.service.TakeoutReader")
        .and()
        .doNotHaveFullyQualifiedName(
            "se.geobook.tracker.tracks.service.TakeoutReader$SemanticVisits")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("tools.jackson.core..")
        .check(APPLICATION);
  }

  @Test
  void databaseAccessBelongsToRepositoriesOrExplicitInfrastructure() {
    noClasses()
        .that()
        .resideOutsideOfPackages("se.geobook.tracker.*.repository..", "db.migration..")
        .and()
        .doNotHaveFullyQualifiedName("se.geobook.tracker.configuration.StorageConfiguration")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "java.sql..",
            "javax.sql..",
            "org.springframework.jdbc..",
            "org.sqlite..",
            "com.zaxxer.hikari..",
            "org.flywaydb..")
        .check(APPLICATION);
  }

  @Test
  void springSecurityBelongsToTheAccessFeature() {
    // The filter chain, its filter and entry point are HTTP handling in the access controllers.
    noClasses()
        .that()
        .resideOutsideOfPackage("se.geobook.tracker.access.controller..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.security.config..",
            "org.springframework.security.web..",
            "org.springframework.security.core..",
            "org.springframework.security.authentication..")
        .check(APPLICATION);
    // Password hashing is the only part of Spring Security that services use.
    noClasses()
        .that()
        .resideOutsideOfPackage("se.geobook.tracker.access.service..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.security.crypto..")
        .check(APPLICATION);
  }

  @Test
  void featuresCannotUseInfrastructureAsAnEscapeFromTheLayers() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "se.geobook.tracker.*.controller..", "se.geobook.tracker.*.service..",
            "se.geobook.tracker.*.repository..", "se.geobook.tracker.*.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "se.geobook.tracker.configuration..",
            "se.geobook.tracker.cli..",
            "db.migration..",
            "se.geobook.tracker")
        .check(APPLICATION);
    classes()
        .that()
        .resideInAPackage("db.migration..")
        .should()
        .beAssignableTo(BaseJavaMigration.class)
        .check(APPLICATION);
  }

  @Test
  void featureModulesAreClosedAndAcyclic() {
    ApplicationModules modules =
        ApplicationModules.of(
            TrackerApplication.class,
            JavaClass.Predicates.resideInAnyPackage(
                "se.geobook.tracker.configuration..", "se.geobook.tracker.cli.."));
    assertThat(modules.getModuleByName("tracks")).isPresent();
    assertThat(modules.getModuleByName("access")).isPresent();
    modules.forEach(module -> assertThat(module.isOpen()).as(module.getDisplayName()).isFalse());
    modules.verify();
  }
}
