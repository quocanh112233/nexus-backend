package com.nexus.presence.infrastructure.redis;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

// Ba script Lua của session đọc từ classpath lua/
final class SessionScripts {

  static final DefaultRedisScript<Long> CONNECT = load("session-connect", Long.class);
  static final DefaultRedisScript<String> DISCONNECT = load("session-disconnect", String.class);
  static final DefaultRedisScript<List> PURGE = load("instance-purge", List.class);

  // Lớp tiện ích, không khởi tạo
  private SessionScripts() {}

  // Nạp một script Lua theo tên file kèm kiểu kết quả
  private static <T> DefaultRedisScript<T> load(String name, Class<T> type) {
    DefaultRedisScript<T> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/" + name + ".lua")));
    script.setResultType(type);
    return script;
  }
}
