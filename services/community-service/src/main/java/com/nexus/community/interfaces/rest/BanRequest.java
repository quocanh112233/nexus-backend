package com.nexus.community.interfaces.rest;

// Body ban, lý do tùy chọn và được kiểm tra độ dài ở miền
public record BanRequest(String reason) {}
