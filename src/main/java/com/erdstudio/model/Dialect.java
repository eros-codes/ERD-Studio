package com.erdstudio.model;

import java.util.List;

public enum Dialect {
    POSTGRESQL("PostgreSQL", List.of(
            "SMALLINT", "INTEGER", "BIGINT", "SERIAL", "BIGSERIAL", "NUMERIC(10, 2)", "REAL", "DOUBLE PRECISION",
            "BOOLEAN", "VARCHAR(255)", "CHAR(1)", "TEXT", "UUID", "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ",
            "INTERVAL", "JSON", "JSONB", "BYTEA", "INET", "TEXT[]")),
    MYSQL("MySQL / MariaDB", List.of(
            "TINYINT", "SMALLINT", "INT", "INT UNSIGNED", "BIGINT", "BIGINT UNSIGNED", "DECIMAL(10, 2)", "FLOAT",
            "DOUBLE", "BOOLEAN", "VARCHAR(255)", "CHAR(36)", "TEXT", "MEDIUMTEXT", "LONGTEXT", "DATE", "TIME",
            "DATETIME", "TIMESTAMP", "YEAR", "JSON", "BLOB", "ENUM('a', 'b')")),
    SQLITE("SQLite", List.of(
            "INTEGER", "REAL", "NUMERIC", "TEXT", "BLOB", "BOOLEAN", "VARCHAR(255)", "DATE", "DATETIME")),
    SQLSERVER("SQL Server", List.of(
            "TINYINT", "SMALLINT", "INT", "BIGINT", "DECIMAL(10, 2)", "MONEY", "FLOAT", "REAL", "BIT",
            "NVARCHAR(255)", "NVARCHAR(MAX)", "VARCHAR(255)", "NCHAR(10)", "UNIQUEIDENTIFIER", "DATE", "TIME",
            "DATETIME2", "DATETIMEOFFSET", "VARBINARY(MAX)", "XML"));

    public final String label;
    public final List<String> types;

    Dialect(String label, List<String> types) {
        this.label = label;
        this.types = types;
    }

    public String quote(String identifier) {
        return switch (this) {
            case MYSQL -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    public String defaultIntType() {
        return this == MYSQL || this == SQLSERVER ? "INT" : "INTEGER";
    }

    public String defaultTextType() {
        return switch (this) {
            case SQLSERVER -> "NVARCHAR(255)";
            case SQLITE -> "TEXT";
            default -> "VARCHAR(255)";
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
