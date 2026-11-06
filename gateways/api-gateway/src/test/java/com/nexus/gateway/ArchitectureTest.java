package com.nexus.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

// Cưỡng chế gateway thuần reactive bằng ArchUnit
@AnalyzeClasses(
    packages = "com.nexus.gateway",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // Kịch bản: gateway chạy WebFlux nên không được dùng API servlet
  // Input: mọi class trong com.nexus.gateway
  // Output: không class nào phụ thuộc jakarta.servlet
  @ArchTest
  static final ArchRule noServletApi =
      noClasses()
          .that()
          .resideInAPackage("com.nexus.gateway..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("jakarta.servlet..");
}
