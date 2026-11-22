-- SERVER-SENE: diagnóstico de esquema D1 (SOLO LECTURA)
-- Ejecutar en Cloudflare D1 Console sobre la base existente "server-sene".
-- No crea, modifica ni elimina tablas o registros.

-- 1) Inventario completo de tablas, índices y definiciones actuales.
SELECT type, name, tbl_name, sql
FROM sqlite_master
WHERE name NOT LIKE 'sqlite_%'
ORDER BY type, name;

-- 2) Columnas de las tablas actuales del módulo de licencias.
PRAGMA table_info(businesses);
PRAGMA table_info(activation_requests);
PRAGMA table_info(activation_codes);
PRAGMA table_info(payments);
PRAGMA table_info(subscriptions);
PRAGMA table_info(notifications);
PRAGMA table_info(app_settings);
PRAGMA table_info(account_recovery_requests);

-- 3) Relaciones e índices existentes.
PRAGMA foreign_key_list(activation_requests);
PRAGMA foreign_key_list(activation_codes);
PRAGMA foreign_key_list(payments);
PRAGMA foreign_key_list(subscriptions);
PRAGMA index_list(businesses);
PRAGMA index_list(activation_requests);
PRAGMA index_list(activation_codes);
