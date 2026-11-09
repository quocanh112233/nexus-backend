package com.nexus.realtime.infrastructure.amqp;

// Định danh instance gateway, dùng chung cho tên queue và event gửi Presence
public record InstanceId(String value) {}
