package com.nexus.common.web;

import java.util.regex.Pattern;

// Che mã invite trong path trước khi ghi log vì mã là thông tin tham gia server
public final class PathMask {

  private static final Pattern INVITE_CODE = Pattern.compile("/invites/[^/]+");

  private PathMask() {}

  // Đổi /invites/{mã thật} thành /invites/{code}, path khác giữ nguyên
  public static String mask(String path) {
    return path == null ? null : INVITE_CODE.matcher(path).replaceAll("/invites/{code}");
  }
}
