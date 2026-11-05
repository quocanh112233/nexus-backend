package com.nexus.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// Cưỡng chế phân tầng DDD của identity-service bằng ArchUnit
@AnalyzeClasses(
    packages = "com.nexus.identity",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // Kịch bản: tầng domain chỉ phụ thuộc JDK
  // Input: mọi class trong com.nexus.identity.domain
  // Output: không class nào phụ thuộc Spring hay jakarta.persistence
  @ArchTest
  static final ArchRule domainDoesNotDependOnFrameworks =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.identity.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
          .allowEmptyShould(true);

  // Kịch bản: tầng application không biết hạ tầng và giao diện
  // Input: mọi class trong com.nexus.identity.application
  // Output: không class nào phụ thuộc infrastructure hay interfaces
  @ArchTest
  static final ArchRule applicationDoesNotDependOnInfrastructureOrInterfaces =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.identity.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.nexus.identity.infrastructure..", "com.nexus.identity.interfaces..")
          .allowEmptyShould(true);

  // Kịch bản: tầng interfaces không gọi thẳng hạ tầng
  // Input: mọi class trong com.nexus.identity.interfaces
  // Output: không class nào phụ thuộc infrastructure
  @ArchTest
  static final ArchRule interfacesDoNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.identity.interfaces..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.nexus.identity.infrastructure..")
          .allowEmptyShould(true);

  // Kịch bản: JPA entity không ra khỏi infrastructure
  // Input: mọi class gắn @Entity trong com.nexus.identity
  // Output: tất cả nằm trong com.nexus.identity.infrastructure
  @ArchTest
  static final ArchRule jpaEntitiesStayInInfrastructure =
      classes()
          .that()
          .areAnnotatedWith("jakarta.persistence.Entity")
          .and()
          .resideInAPackage("com.nexus.identity..")
          .should()
          .resideInAPackage("com.nexus.identity.infrastructure..")
          .allowEmptyShould(true);
}
