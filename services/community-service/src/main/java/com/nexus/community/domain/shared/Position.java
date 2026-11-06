package com.nexus.community.domain.shared;

// Vị trí sắp xếp không âm
public record Position(int value) {

  // Từ chối vị trí âm
  public Position {
    if (value < 0) {
      throw new DomainValidationException("position must be >= 0");
    }
  }
}
