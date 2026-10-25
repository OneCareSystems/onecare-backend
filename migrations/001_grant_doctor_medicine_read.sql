-- =====================================================================
-- Migration 001: grant DOCTOR the MEDICINE_READ permission
--
-- Why this is needed:
--   MEDICINE_READ was added to the static defaults in
--   src/main/java/com/onecare/backend/security/RolePermission.java, but
--   that only affects FRESH databases:
--     * RolePermissionSeeder skips any role that already has rows in
--       role_permissions (so Super Admin customizations survive), and
--     * AppUserDetailsService reads the PERSISTED rows, falling back to
--       the static defaults only while a role has none.
--   A database seeded before this change therefore keeps doctors without
--   MEDICINE_READ and they receive 403 for medicine reads even though the
--   code grants the permission.
--
--   Spring SQL initialization is disabled in staging/prod
--   (spring.sql.init.mode=never), so this must NOT be added to data.sql
--   or schema.sql: apply it through the deployment migration process.
--
-- How to apply:
--   Run against the target database once per environment. The statement
--   is idempotent (guarded by NOT EXISTS, unique constraint
--   uq_role_permission) so re-running it is safe.
--
-- Verified by: SeededPermissionUpgradeMigrationIntegrationTest, which
--   executes this exact file against the test database.
-- =====================================================================

INSERT INTO role_permissions (role, permission)
SELECT 'DOCTOR', 'MEDICINE_READ' FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM role_permissions
    WHERE role = 'DOCTOR'
      AND permission = 'MEDICINE_READ'
);
