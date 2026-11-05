#!/bin/bash
set -euo pipefail
# Tạo schema $1 thuộc user $2 (mật khẩu $3) và chặn mọi user khác truy cập
create() {
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
CREATE USER $2 WITH PASSWORD '$3';
CREATE SCHEMA $1 AUTHORIZATION $2;
REVOKE ALL ON SCHEMA $1 FROM PUBLIC;
ALTER ROLE $2 SET search_path = $1;
SQL
}
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC;"
create identity      identity_svc      "$IDENTITY_DB_PASSWORD"
create community     community_svc     "$COMMUNITY_DB_PASSWORD"
create messaging     messaging_svc     "$MESSAGING_DB_PASSWORD"
create notification  notification_svc  "$NOTIFICATION_DB_PASSWORD"
