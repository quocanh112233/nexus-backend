# ADR-006: Maven multi-module

Ghi lại lựa chọn build tool cho backend.

- **Trạng thái:** Accepted — người dùng duyệt (P-03)

## Bối cảnh
- Monorepo nhiều module (service, lib, proto). Cần sinh code protobuf/gRPC, build nhanh trong CI.

## Quyết định
- **Maven multi-module**: `pom.xml` gốc làm parent + `dependencyManagement` (Spring Boot BOM, Spring Cloud BOM, gRPC BOM).
- CI build từng service: `mvn -pl services/<svc> -am verify` (`-am` build cả module phụ thuộc như `proto`, `libs`).

## Hệ quả
- (+) Cú pháp khai báo, dễ đọc, nhiều ví dụ, nhà tuyển dụng Java quen thuộc.
- (+) Plugin protobuf Maven ổn định.
- (-) Chậm hơn Gradle (không có build cache mức task). Bù lại: cache `~/.m2` + path filter.
- Chuyển Gradle sau này là việc cơ học, không ảnh hưởng kiến trúc.
