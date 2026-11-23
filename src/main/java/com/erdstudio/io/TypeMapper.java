package com.erdstudio.io;

import com.erdstudio.model.Dialect;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort translation of column types between dialects on export. */
public final class TypeMapper {
    private static final Pattern TYPE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_ ]*?)\\s*(\\(.*\\))?\\s*(\\[\\])?\\s*$");

    private TypeMapper() {}

    public static String map(String type, Dialect to) {
        if (type == null || type.isBlank()) return to.defaultTextType();
        Matcher m = TYPE.matcher(type);
        if (!m.matches()) return type;
        String base = m.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String args = m.group(2) == null ? "" : m.group(2);
        boolean array = m.group(3) != null;
        String argsU = args.toUpperCase(Locale.ROOT).replace(" ", "");
        boolean max = argsU.equals("(MAX)");

        if (array && to != Dialect.POSTGRESQL) return to == Dialect.MYSQL ? "JSON" : text(to);

        boolean unsigned = base.endsWith(" UNSIGNED");
        if (unsigned && to != Dialect.MYSQL) base = base.substring(0, base.length() - " UNSIGNED".length());

        switch (base) {
            case "BOOLEAN", "BOOL":
                return to == Dialect.SQLSERVER ? "BIT" : "BOOLEAN";
            case "BIT":
                if (!args.isEmpty() && !argsU.equals("(1)")) break;
                return switch (to) {
                    case POSTGRESQL -> "BOOLEAN";
                    case MYSQL -> "TINYINT(1)";
                    case SQLITE -> "INTEGER";
                    case SQLSERVER -> "BIT";
                };
            case "TINYINT":
                return to == Dialect.POSTGRESQL ? "SMALLINT" : to == Dialect.SQLITE ? "INTEGER" : type;
            case "MEDIUMINT":
                return to == Dialect.MYSQL ? type : to == Dialect.SQLSERVER ? "INT" : "INTEGER";
            case "INT", "INTEGER", "INT4":
                return to == Dialect.MYSQL || to == Dialect.SQLSERVER ? "INT" + (unsigned && to == Dialect.MYSQL ? " UNSIGNED" : "") : "INTEGER";
            case "INT8":
                return "BIGINT";
            case "INT2":
                return "SMALLINT";
            case "TEXT", "MEDIUMTEXT", "LONGTEXT", "TINYTEXT", "CLOB", "NTEXT":
                if (to == Dialect.MYSQL) return base.equals("NTEXT") || base.equals("CLOB") ? "LONGTEXT" : base;
                return text(to);
            case "VARCHAR", "NVARCHAR", "CHARACTER VARYING", "VARCHAR2":
                if (max || args.isEmpty()) {
                    if (to == Dialect.SQLSERVER) return "NVARCHAR(" + (args.isEmpty() ? "255" : "MAX") + ")";
                    if (to == Dialect.MYSQL) return max ? "LONGTEXT" : "VARCHAR(255)";
                    return to == Dialect.POSTGRESQL && args.isEmpty() ? "VARCHAR" : "TEXT";
                }
                return (to == Dialect.SQLSERVER ? "NVARCHAR" : "VARCHAR") + args;
            case "CHAR", "NCHAR", "CHARACTER":
                return (to == Dialect.SQLSERVER && base.equals("NCHAR") ? "NCHAR" : "CHAR") + args;
            case "UUID", "UNIQUEIDENTIFIER":
                return switch (to) {
                    case POSTGRESQL -> "UUID";
                    case MYSQL -> "CHAR(36)";
                    case SQLITE -> "TEXT";
                    case SQLSERVER -> "UNIQUEIDENTIFIER";
                };
            case "JSON", "JSONB":
                return switch (to) {
                    case POSTGRESQL -> base;
                    case MYSQL -> "JSON";
                    case SQLITE -> "TEXT";
                    case SQLSERVER -> "NVARCHAR(MAX)";
                };
            case "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "DATETIMEOFFSET":
                return switch (to) {
                    case POSTGRESQL -> "TIMESTAMPTZ";
                    case MYSQL -> "TIMESTAMP";
                    case SQLITE -> "DATETIME";
                    case SQLSERVER -> "DATETIMEOFFSET";
                };
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE", "DATETIME", "DATETIME2", "SMALLDATETIME":
                return switch (to) {
                    case POSTGRESQL -> "TIMESTAMP" + args;
                    case MYSQL -> base.equals("DATETIME") || base.equals("TIMESTAMP") ? base + args : "DATETIME";
                    case SQLITE -> "DATETIME";
                    case SQLSERVER -> "DATETIME2";
                };
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8":
                return switch (to) {
                    case POSTGRESQL -> "DOUBLE PRECISION";
                    case MYSQL -> "DOUBLE";
                    case SQLITE -> "REAL";
                    case SQLSERVER -> "FLOAT";
                };
            case "FLOAT", "FLOAT4", "REAL":
                return to == Dialect.SQLITE ? "REAL" : to == Dialect.POSTGRESQL ? "REAL" : base + args;
            case "BYTEA", "BLOB", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "VARBINARY", "BINARY", "IMAGE":
                if ((base.equals("VARBINARY") || base.equals("BINARY")) && !max && !args.isEmpty() && to != Dialect.POSTGRESQL && to != Dialect.SQLITE)
                    return base + args;
                return switch (to) {
                    case POSTGRESQL -> "BYTEA";
                    case MYSQL -> base.endsWith("BLOB") ? base : "LONGBLOB";
                    case SQLITE -> "BLOB";
                    case SQLSERVER -> "VARBINARY(MAX)";
                };
            case "ENUM", "SET":
                return to == Dialect.MYSQL ? type : to == Dialect.SQLSERVER ? "NVARCHAR(255)" : to == Dialect.SQLITE ? "TEXT" : "VARCHAR(255)";
            case "MONEY", "SMALLMONEY":
                return switch (to) {
                    case POSTGRESQL, SQLSERVER -> "MONEY";
                    case MYSQL -> "DECIMAL(19, 4)";
                    case SQLITE -> "NUMERIC";
                };
            case "NUMERIC", "DECIMAL", "NUMBER":
                return to == Dialect.SQLITE ? "NUMERIC" : (base.equals("NUMBER") ? "NUMERIC" : base) + args;
            case "YEAR":
                return to == Dialect.MYSQL ? "YEAR" : "SMALLINT";
            case "XML", "INET", "CIDR", "MACADDR", "INTERVAL", "TSVECTOR", "CITEXT":
                return to == Dialect.POSTGRESQL || (to == Dialect.SQLSERVER && base.equals("XML")) ? type : text(to);
            default:
                break;
        }
        return type;
    }

    /** Translates the most common DEFAULT expressions (now(), uuid functions, booleans, PG casts). */
    public static String mapDefault(String value, Dialect to) {
        String v = value;
        if (to != Dialect.POSTGRESQL) {
            v = v.replaceAll("::\\s*[A-Za-z_][A-Za-z0-9_ ]*(\\([^)]*\\))?(\\[\\])?", "").trim();
        }
        String u = v.toUpperCase(Locale.ROOT).replace(" ", "");
        String bare = u.startsWith("(") && u.endsWith(")") ? u.substring(1, u.length() - 1) : u;
        switch (bare) {
            case "NOW()", "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP()", "GETDATE()", "SYSDATETIME()", "LOCALTIMESTAMP":
                return switch (to) {
                    case POSTGRESQL -> bare.equals("CURRENT_TIMESTAMP") ? "CURRENT_TIMESTAMP" : "now()";
                    case SQLSERVER -> "GETDATE()";
                    default -> "CURRENT_TIMESTAMP";
                };
            case "GEN_RANDOM_UUID()", "UUID_GENERATE_V4()", "NEWID()", "UUID()":
                return switch (to) {
                    case POSTGRESQL -> "gen_random_uuid()";
                    case SQLSERVER -> "NEWID()";
                    case MYSQL -> "(UUID())";
                    case SQLITE -> "(lower(hex(randomblob(16))))";
                };
            case "TRUE", "FALSE":
                if (to == Dialect.SQLSERVER || to == Dialect.SQLITE) return bare.equals("TRUE") ? "1" : "0";
                return bare;
            default:
                return v;
        }
    }

    private static String text(Dialect to) {
        return to == Dialect.SQLSERVER ? "NVARCHAR(MAX)" : "TEXT";
    }

    public static boolean isIntegerType(String type) {
        String u = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return u.startsWith("INT") || u.startsWith("BIGINT") || u.startsWith("SMALLINT") || u.startsWith("TINYINT")
                || u.startsWith("MEDIUMINT") || u.startsWith("SERIAL") || u.startsWith("BIGSERIAL") || u.equals("INTEGER");
    }
}
