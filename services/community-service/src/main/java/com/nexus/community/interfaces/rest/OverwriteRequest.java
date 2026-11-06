package com.nexus.community.interfaces.rest;

// Body đặt overwrite kênh, bit vắng là 0, tính hợp lệ của bit kiểm ở use case
public record OverwriteRequest(long allow, long deny) {}
