package com.nexus.presence;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// Cưỡng chế phân tầng của presence-service bằng ArchUnit
@AnalyzeClasses(
    packages = "com.nexus.presence",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // Kịch bản: tầng domain thuần Java, không biết tầng khác
  // Input: mọi class trong com.nexus.presence.domain
  // Output: không class nào phụ thuộc application, infrastructure hay interfaces
  @ArchTest
  static final ArchRule domainDoesNotDependOnOuterLayers =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.presence.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.nexus.presence.application..",
              "com.nexus.presence.infrastructure..",
              "com.nexus.presence.interfaces..");

  // Kịch bản: tầng application chỉ biết domain và Spring, không biết hạ tầng hay giao diện
  // Input: mọi class trong com.nexus.presence.application
  // Output: không class nào phụ thuộc infrastructure hay interfaces
  @ArchTest
  static final ArchRule applicationDoesNotDependOnOuterLayers =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.presence.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.nexus.presence.infrastructure..", "com.nexus.presence.interfaces..");

  // Kịch bản: class sinh từ proto và io.grpc chỉ xuất hiện ở tầng infrastructure
  // Input: mọi class trong com.nexus.presence.application
  // Output: không class nào phụ thuộc com.nexus.proto hay io.grpc
  @ArchTest
  static final ArchRule applicationDoesNotDependOnProto =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.presence.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.nexus.proto..", "io.grpc..");

  // Kịch bản: tầng interfaces chỉ gọi application, không biết hạ tầng
  // Input: mọi class trong com.nexus.presence.interfaces
  // Output: không class nào phụ thuộc infrastructure
  @ArchTest
  static final ArchRule interfacesDoNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.presence.interfaces..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.nexus.presence.infrastructure..");
}
