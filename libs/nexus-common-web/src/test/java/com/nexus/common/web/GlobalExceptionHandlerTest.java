package com.nexus.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Kiểm tra GlobalExceptionHandler và RequestIdFilter qua MockMvc standalone
class GlobalExceptionHandlerTest {

  private MockMvc mvc;

  // Dựng MockMvc với hai advice, advice chung đặt trước advice riêng để chứng minh thứ tự ưu tiên
  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new FakeController())
            .setControllerAdvice(new GlobalExceptionHandler(), new SpecificAdvice())
            .addFilters(new RequestIdFilter())
            .build();
  }

  // Kịch bản: ApiException được đổi thành Problem Details
  // Input: GET /taken kèm header X-Request-Id=req-123
  // Output: 409, code=EMAIL_TAKEN, type kết thúc /email-taken, requestId=req-123
  @Test
  void apiExceptionBecomesProblemDetails() throws Exception {
    mvc.perform(get("/taken").header("X-Request-Id", "req-123"))
        .andExpect(status().isConflict())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
        .andExpect(jsonPath("$.type").value("https://nexus.dev/errors/email-taken"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.instance").value("/taken"))
        .andExpect(jsonPath("$.requestId").value("req-123"))
        .andExpect(header().string("X-Request-Id", "req-123"));
  }

  // Kịch bản: body sai validation
  // Input: POST /users với email rỗng
  // Output: 400, code=VALIDATION_FAILED, errors[0].field=email
  @Test
  void invalidBodyListsFieldErrors() throws Exception {
    mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("email"));
  }

  // Kịch bản: body không phải JSON hợp lệ
  // Input: POST /users với nội dung "not-json"
  // Output: 400, code=VALIDATION_FAILED
  @Test
  void unreadableBodyIsValidationFailed() throws Exception {
    mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // Kịch bản: exception lạ không được lộ message
  // Input: GET /boom ném IllegalStateException("secret-internal-detail")
  // Output: 500, code=INTERNAL_ERROR, body không chứa secret-internal-detail
  @Test
  void unexpectedExceptionDoesNotLeakMessage() throws Exception {
    String body =
        mvc.perform(get("/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("secret-internal-detail");
  }

  // Kịch bản: không gửi X-Request-Id thì filter tự sinh
  // Input: GET /taken không có header
  // Output: header X-Request-Id là UUID và khớp requestId trong body
  @Test
  void requestIdGeneratedWhenMissing() throws Exception {
    var response = mvc.perform(get("/taken")).andReturn().getResponse();
    String id = response.getHeader("X-Request-Id");
    assertThat(UUID.fromString(id)).isNotNull();
    assertThat(response.getContentAsString()).contains("\"requestId\":\"" + id + "\"");
  }

  // Kịch bản: header X-Request-Id chứa ký tự lạ bị bỏ qua
  // Input: GET /taken với X-Request-Id="bad id\tinjected"
  // Output: header response là UUID mới, không phải giá trị gửi lên
  @Test
  void unsafeRequestIdIsReplaced() throws Exception {
    var response =
        mvc.perform(get("/taken").header("X-Request-Id", "bad id\tinjected"))
            .andReturn()
            .getResponse();
    assertThat(UUID.fromString(response.getHeader("X-Request-Id"))).isNotNull();
  }

  // Kịch bản: advice riêng có ưu tiên cao hơn thì thắng, advice chung vẫn bắt exception lạ
  // Input: GET /specific ném SpecificException, GET /boom ném IllegalStateException
  // Output: /specific trả 418 code=SPECIFIC, /boom trả 500 INTERNAL_ERROR
  @Test
  void higherPrecedenceAdviceWinsOverGenericOne() throws Exception {
    mvc.perform(get("/specific"))
        .andExpect(status().isIAmATeapot())
        .andExpect(jsonPath("$.code").value("SPECIFIC"));
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  // Kịch bản: route không tồn tại
  // Input: GET /no-such-route
  // Output: 404, code=NOT_FOUND, body Problem Details
  @Test
  void unmatchedRouteIsNotFound() throws Exception {
    mvc.perform(get("/no-such-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.type").value("https://nexus.dev/errors/not-found"));
  }

  // Kịch bản: sai HTTP method
  // Input: POST /taken trong khi route chỉ nhận GET
  // Output: 405, code=METHOD_NOT_ALLOWED
  @Test
  void wrongMethodIsMethodNotAllowed() throws Exception {
    mvc.perform(post("/taken"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }

  // Kịch bản: thiếu @RequestParam bắt buộc
  // Input: GET /search không có tham số q
  // Output: 400, code=VALIDATION_FAILED
  @Test
  void missingRequestParamIsValidationFailed() throws Exception {
    mvc.perform(get("/search"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // Kịch bản: path variable UUID sai định dạng
  // Input: GET /items/not-a-uuid
  // Output: 400, code=VALIDATION_FAILED
  @Test
  void badUuidPathVariableIsValidationFailed() throws Exception {
    mvc.perform(get("/items/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // Kịch bản: lỗi Spring Security phải đi tiếp cho entry point xử lý
  // Input: GET /denied ném AccessDeniedException
  // Output: exception lan ra ngoài, không bị đổi thành 500 INTERNAL_ERROR
  @Test
  void accessDeniedIsPropagatedNotConvertedTo500() {
    assertThatThrownBy(() -> mvc.perform(get("/denied")))
        .hasRootCauseInstanceOf(AccessDeniedException.class);
  }

  // Kịch bản: GlobalExceptionHandler mang thứ tự thấp nhất
  // Input: đọc annotation @Order trên GlobalExceptionHandler
  // Output: giá trị bằng Ordered.LOWEST_PRECEDENCE
  @Test
  void globalHandlerHasLowestPrecedence() {
    Order order = GlobalExceptionHandler.class.getAnnotation(Order.class);
    assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }

  // Controller giả ném các loại exception cần kiểm tra
  @RestController
  static class FakeController {

    // Ném ApiException EMAIL_TAKEN
    @GetMapping("/taken")
    String taken() {
      throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email already registered");
    }

    // Ném exception lạ chứa thông tin nhạy cảm
    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("secret-internal-detail");
    }

    // Ném exception do advice riêng xử lý
    @GetMapping("/specific")
    String specific() {
      throw new SpecificException();
    }

    // Nhận tham số bắt buộc q
    @GetMapping("/search")
    String search(@RequestParam String q) {
      return q;
    }

    // Nhận id kiểu UUID trong path
    @GetMapping("/items/{id}")
    String item(@PathVariable UUID id) {
      return id.toString();
    }

    // Ném AccessDeniedException của Spring Security
    @GetMapping("/denied")
    String denied() {
      throw new AccessDeniedException("no");
    }

    // Nhận body cần validate
    @PostMapping("/users")
    String create(@Valid @RequestBody CreateUser body) {
      return body.email();
    }
  }

  // Body giả với ràng buộc not blank
  record CreateUser(@NotBlank String email) {}

  // Exception riêng của service giả
  static class SpecificException extends RuntimeException {}

  // Advice riêng của service giả, ưu tiên cao nhất
  @RestControllerAdvice
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class SpecificAdvice {

    // Đổi SpecificException thành 418 code=SPECIFIC
    @ExceptionHandler(SpecificException.class)
    ResponseEntity<String> handle() {
      return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"code\":\"SPECIFIC\"}");
    }
  }
}
