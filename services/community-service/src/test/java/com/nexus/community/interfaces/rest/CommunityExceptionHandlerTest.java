package com.nexus.community.interfaces.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Kiểm tra CommunityExceptionHandler đổi lỗi khóa của cơ sở dữ liệu thành 409 VERSION_CONFLICT
class CommunityExceptionHandlerTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Thrower())
          .setControllerAdvice(new CommunityExceptionHandler())
          .build();

  // Kịch bản: deadlock hoặc hết hạn chờ khóa và xung đột phiên bản đều là xung đột đồng thời
  // Input: controller giả ném CannotAcquireLockException rồi OptimisticLockingFailureException
  // Output: cả hai 409 với code VERSION_CONFLICT
  @Test
  void lockFailuresMapTo409() throws Exception {
    for (String path : new String[] {"/pessimistic", "/optimistic"}) {
      mvc.perform(get(path))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }
  }

  // Controller giả chỉ để ném hai loại lỗi khóa
  @RestController
  static class Thrower {

    // Ném lỗi khóa bi quan như deadlock
    @GetMapping("/pessimistic")
    void pessimistic() {
      throw new CannotAcquireLockException("deadlock");
    }

    // Ném lỗi khóa lạc quan như sai phiên bản
    @GetMapping("/optimistic")
    void optimistic() {
      throw new OptimisticLockingFailureException("stale");
    }
  }
}
