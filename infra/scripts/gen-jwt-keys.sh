#!/bin/bash
set -euo pipefail
# Sinh khóa RS256 PKCS#8 2048-bit cho identity vào infra/secrets (chỉ dùng local)
gen() {
  local dir
  dir="$(cd "$(dirname "$0")/.." && pwd)/secrets"
  mkdir -p "$dir"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/jwt-private.pem"
  chmod 644 "$dir/jwt-private.pem"
}
gen
