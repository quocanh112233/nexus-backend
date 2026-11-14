package com.nexus.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// Cưỡng chế phân tầng DDD của notification-service bằng ArchUnit
@AnalyzeClasses(
    packages = "com.nexus.notification",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String TRANSACTIONAL =
      "org.springframework.transaction.annotation.Transactional";

  // Kịch bản: tầng domain chỉ phụ thuộc JDK và uuid-creator
  // Input: mọi class trong com.nexus.notification.domain
  // Output: không class nào phụ thuộc gói nào ngoài domain, java và uuid-creator
  @ArchTest
  static final ArchRule domainDependsOnlyOnJdkAndUuidCreator =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.notification.domain..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages(
              "com.nexus.notification.domain..", "java..", "com.github.f4b6a3.uuid..");

  // Kịch bản: tầng application chỉ biết domain
  // Input: mọi class trong com.nexus.notification.application
  // Output: không class nào phụ thuộc infrastructure hay interfaces
  @ArchTest
  static final ArchRule applicationDependsOnlyOnDomain =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.notification.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.nexus.notification.infrastructure..", "com.nexus.notification.interfaces..");

  // Kịch bản: class sinh từ proto và io.grpc chỉ xuất hiện ở tầng interfaces và infrastructure
  // Input: mọi class trong com.nexus.notification.domain và application
  // Output: không class nào phụ thuộc com.nexus.proto hay io.grpc
  @ArchTest
  static final ArchRule domainAndApplicationDoNotDependOnProto =
      noClasses()
          .that()
          .resideInAnyPackage(
              "com.nexus.notification.domain..", "com.nexus.notification.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.nexus.proto..", "io.grpc..");

  // Kịch bản: tầng interfaces không gọi thẳng hạ tầng
  // Input: mọi class trong com.nexus.notification.interfaces
  // Output: không class nào phụ thuộc infrastructure
  @ArchTest
  static final ArchRule interfacesDoNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.notification.interfaces..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.nexus.notification.infrastructure..");

  // Kịch bản: giao dịch cấp class chỉ mở ở use case của tầng application
  // Input: mọi class ngoài com.nexus.notification.application
  // Output: không class nào gắn @Transactional
  @ArchTest
  static final ArchRule transactionalClassesOnlyInApplication =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.nexus.notification.application..")
          .should()
          .beAnnotatedWith(TRANSACTIONAL);

  // Kịch bản: giao dịch cấp method chỉ mở ở use case của tầng application
  // Input: mọi method khai báo ngoài com.nexus.notification.application
  // Output: không method nào gắn @Transactional
  @ArchTest
  static final ArchRule transactionalMethodsOnlyInApplication =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideOutsideOfPackage("com.nexus.notification.application..")
          .should()
          .beAnnotatedWith(TRANSACTIONAL);
}
