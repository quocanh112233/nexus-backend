ALTER TABLE roles DROP CONSTRAINT ux_roles_server_position;

ALTER TABLE roles
    ADD CONSTRAINT ux_roles_server_position UNIQUE (server_id, position)
    DEFERRABLE INITIALLY DEFERRED;
