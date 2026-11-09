package com.nexus.realtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// Cưỡng chế phân tầng của realtime-gateway bằng ArchUnit
@AnalyzeClasses(
    packages = "com.nexus.realtime",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // Kịch bản: tầng application chỉ biết JDK và Spring, không biết hạ tầng hay giao diện
  // Input: mọi class trong com.nexus.realtime.application
  // Output: không class nào phụ thuộc infrastructure hay interfaces
  @ArchTest
  static final ArchRule applicationDoesNotDependOnOuterLayers =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.realtime.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.nexus.realtime.infrastructure..", "com.nexus.realtime.interfaces..");

  // Kịch bản: class sinh từ proto và io.grpc chỉ xuất hiện ở tầng infrastructure
  // Input: mọi class trong com.nexus.realtime.application
  // Output: không class nào phụ thuộc com.nexus.proto hay io.grpc
  @ArchTest
  static final ArchRule applicationDoesNotDependOnProto =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.realtime.application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.nexus.proto..", "io.grpc..");

  // Kịch bản: tầng interfaces chỉ gọi application, không biết hạ tầng
  // Input: mọi class trong com.nexus.realtime.interfaces
  // Output: không class nào phụ thuộc infrastructure
  @ArchTest
  static final ArchRule interfacesDoNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.realtime.interfaces..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.nexus.realtime.infrastructure..");
}
