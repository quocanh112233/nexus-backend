package com.nexus.community.interfaces.rest;

import com.nexus.community.application.channel.CreateCategoryUseCase;
import com.nexus.community.application.channel.DeleteCategoryUseCase;
import com.nexus.community.application.channel.UpdateCategoryUseCase;
import com.nexus.community.application.server.CategoryView;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint tạo, sửa và xóa category của server, chỉ chủ server được phép
@RestController
@RequestMapping("/api/v1/servers/{serverId}/categories")
class CategoryController {

  private final CreateCategoryUseCase createCategory;
  private final UpdateCategoryUseCase updateCategory;
  private final DeleteCategoryUseCase deleteCategory;

  // Nhận các use case qua constructor
  CategoryController(
      CreateCategoryUseCase createCategory,
      UpdateCategoryUseCase updateCategory,
      DeleteCategoryUseCase deleteCategory) {
    this.createCategory = createCategory;
    this.updateCategory = updateCategory;
    this.deleteCategory = deleteCategory;
  }

  // Tạo category, trả 201 kèm Location
  @PostMapping
  ResponseEntity<CategoryResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @Valid @RequestBody CreateCategoryRequest request) {
    CategoryView view =
        createCategory.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            new CategoryName(request.name()),
            request.position());
    return ResponseEntity.created(
            URI.create("/api/v1/servers/" + serverId + "/categories/" + view.id()))
        .body(CategoryResponse.from(serverId, view));
  }

  // Sửa tên hoặc vị trí category
  @PatchMapping("/{categoryId}")
  CategoryResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID categoryId,
      @Valid @RequestBody UpdateCategoryRequest request) {
    return CategoryResponse.from(
        serverId,
        updateCategory.execute(
            new ServerId(serverId),
            new CategoryId(categoryId),
            UserId.of(jwt.getSubject()),
            request.name() == null ? null : new CategoryName(request.name()),
            request.position() == null ? null : new Position(request.position())));
  }

  // Xóa category, trả 204
  @DeleteMapping("/{categoryId}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID categoryId) {
    deleteCategory.execute(
        new ServerId(serverId), new CategoryId(categoryId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}
