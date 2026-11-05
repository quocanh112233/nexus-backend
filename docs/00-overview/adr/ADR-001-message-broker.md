# ADR-001: Chọn RabbitMQ làm message broker

Ghi lại lý do chọn broker cho giao tiếp bất đồng bộ giữa các service.

- **Trạng thái:** Accepted — người dùng duyệt (P-01)

## Bối cảnh
- Service giao tiếp bất đồng bộ bằng event (D-08). Cần at-least-once, retry, DLQ, chạy local nhẹ.
- Mục tiêu học số 3 là "message queue hoạt động thế nào"; người học một mình, máy local.
- Lưu lượng nhỏ (dự án học/demo), không cần replay lịch sử event dài hạn.

## Quyết định
Dùng **RabbitMQ 4.x**: mỗi service producer có một **topic exchange** `<service>.events`; mỗi service consumer có **quorum queue** riêng `<consumer>.<producer>.events` kèm DLQ. Chi tiết: [messaging-deep-dive.md](../../02-architecture/messaging-deep-dive.md).

## Hệ quả
- (+) Một container, Management UI cho phép *nhìn thấy* message, ack, DLQ: tốt cho học và demo phỏng vấn.
- (+) Spring AMQP tích hợp sẵn retry, DLX, publisher confirm.
- (+) Routing linh hoạt (topic exchange, binding theo routing key).
- (-) Không có replay theo offset như Kafka; projection mới phải dựng bằng gRPC/backfill.
- (-) Thứ tự chỉ đảm bảo trong một queue với một consumer; cần thiết kế consumer chịu được đảo thứ tự (dùng `version`).
- Nếu sau này muốn học Kafka: thay lớp `infrastructure/messaging`, envelope và outbox giữ nguyên.
