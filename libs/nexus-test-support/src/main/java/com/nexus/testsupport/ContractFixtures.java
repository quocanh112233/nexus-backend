package com.nexus.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// Đọc payload mẫu contracts/events/<eventType>.json và so khớp cấu trúc payload thật với mẫu
public final class ContractFixtures {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  // Lớp tiện ích, không khởi tạo
  private ContractFixtures() {}

  // Đọc payload mẫu của một event từ classpath, thiếu file thì ném lỗi
  public static Map<String, Object> payload(String eventType) {
    String path = "contracts/events/" + eventType + ".json";
    try (InputStream in = ContractFixtures.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("thiếu fixture " + path);
      }
      return MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("không đọc được fixture " + path, e);
    }
  }

  // Kiểm tra payload thật cùng tập khóa và cùng kiểu JSON với mẫu, null trong mẫu nghĩa là nullable
  public static void assertSameShape(Map<String, Object> actual, String eventType) {
    String error = diff("", actual, payload(eventType));
    if (error != null) {
      throw new AssertionError(eventType + " lệch hợp đồng: " + error);
    }
  }

  // Trả mô tả chỗ lệch đầu tiên giữa giá trị thật và mẫu, khớp thì trả null
  private static String diff(String at, Object actual, Object expected) {
    if (expected == null) {
      return null;
    }
    String kind = kind(expected);
    if (!kind.equals(kind(actual))) {
      return at + " cần " + kind + " nhưng là " + kind(actual);
    }
    if (expected instanceof Map<?, ?> e) {
      Map<?, ?> a = (Map<?, ?>) actual;
      if (!new TreeSet<>(keys(a)).equals(new TreeSet<>(keys(e)))) {
        return at + " khóa " + keys(a) + " khác mẫu " + keys(e);
      }
      for (Map.Entry<?, ?> entry : e.entrySet()) {
        String error = diff(at + "/" + entry.getKey(), a.get(entry.getKey()), entry.getValue());
        if (error != null) {
          return error;
        }
      }
    } else if (expected instanceof List<?> e && !e.isEmpty() && !((List<?>) actual).isEmpty()) {
      return diff(at + "[]", ((List<?>) actual).get(0), e.get(0));
    }
    return null;
  }

  // Tên kiểu JSON của một giá trị Java
  private static String kind(Object value) {
    return switch (value) {
      case null -> "null";
      case Map<?, ?> m -> "object";
      case List<?> l -> "array";
      case Number n -> "number";
      case Boolean b -> "boolean";
      default -> "string";
    };
  }

  // Tập khóa của map dạng chuỗi
  private static List<String> keys(Map<?, ?> map) {
    return map.keySet().stream().map(String::valueOf).toList();
  }
}
