package com.erdstudio.io;

import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.RefAction;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/** Reverse-engineers a live PostgreSQL or MySQL/MariaDB database into a diagram (read-only). */
public final class DbReverse {

    public record Conn(Dialect dialect, String host, int port, String database, String user, String password,
                       String schema, boolean ssl) {
        public String label() {
            return dialect.label + " · " + user + "@" + host + ":" + port + "/" + database;
        }
    }

    private DbReverse() {}

    public static String url(Conn c) {
        String host = c.host() == null || c.host().isBlank() ? "localhost" : c.host().trim();
        if (c.dialect() == Dialect.MYSQL) {
            String prefix = System.getProperty("erd.mysql.jdbcPrefix", "jdbc:mysql://");
            return prefix + host + ":" + c.port() + "/" + c.database().trim()
                    + "?useSSL=" + c.ssl() + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&useInformationSchema=true&connectTimeout=8000";
        }
        return "jdbc:postgresql://" + host + ":" + c.port() + "/" + c.database().trim()
                + "?sslmode=" + (c.ssl() ? "require" : "prefer") + "&connectTimeout=8&ApplicationName=ERD%20Studio";
    }

    public static Connection open(Conn c) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", c.user() == null ? "" : c.user());
        p.setProperty("password", c.password() == null ? "" : c.password());
        DriverManager.setLoginTimeout(10);
        Connection con = DriverManager.getConnection(url(c), p);
        con.setReadOnly(true);
        return con;
    }

    /** @return server product + version, for the "Test connection" button */
    public static String test(Conn c) throws SQLException {
        try (Connection con = open(c)) {
            DatabaseMetaData md = con.getMetaData();
            int tables = 0;
            try (ResultSet rs = md.getTables(catalog(c), schema(c), "%", new String[]{"TABLE"})) {
                while (rs.next()) tables++;
            }
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion() + " — " + tables + " tables";
        }
    }

    public static List<String> schemas(Conn c) throws SQLException {
        List<String> out = new ArrayList<>();
        if (c.dialect() != Dialect.POSTGRESQL) return out;
        try (Connection con = open(c); ResultSet rs = con.getMetaData().getSchemas()) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                if (!s.startsWith("pg_") && !s.equals("information_schema")) out.add(s);
            }
        }
        return out;
    }

    private static String catalog(Conn c) {
        return c.dialect() == Dialect.MYSQL ? c.database().trim() : null;
    }

    private static String schema(Conn c) {
        if (c.dialect() == Dialect.MYSQL) return null;
        return c.schema() == null || c.schema().isBlank() ? "public" : c.schema().trim();
    }

    public static ImportResult read(Conn c, Consumer<String> progress) throws SQLException {
        List<String> warnings = new ArrayList<>();
        Diagram d = new Diagram();
        d.dialect = c.dialect();
        d.name = c.database();
        try (Connection con = open(c)) {
            DatabaseMetaData md = con.getMetaData();
            String cat = catalog(c), sch = schema(c);
            Map<String, Table> tables = new LinkedHashMap<>();
            try (ResultSet rs = md.getTables(cat, sch, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    Table t = new Table(rs.getString("TABLE_NAME"));
                    String remarks = rs.getString("REMARKS");
                    t.comment = remarks == null ? "" : remarks;
                    tables.put(t.name, t);
                }
            }
            int n = 0;
            for (Table t : tables.values()) {
                progress.accept("Reading " + t.name + " (" + (++n) + "/" + tables.size() + ")");
                if (c.dialect() == Dialect.MYSQL) mysqlColumns(con, c.database().trim(), t);
                else pgColumns(md, cat, sch, t);
                try (ResultSet rs = md.getPrimaryKeys(cat, sch, t.name)) {
                    Map<Integer, String> pk = new java.util.TreeMap<>();
                    while (rs.next()) pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                    for (String name : pk.values()) {
                        Column col = t.columnByName(name);
                        if (col != null) { col.primaryKey = true; col.notNull = true; }
                    }
                }
                d.tables.add(t);
            }
            // foreign keys
            Set<String> fkNames = new HashSet<>();
            for (Table t : tables.values()) {
                try (ResultSet rs = md.getImportedKeys(cat, sch, t.name)) {
                    Map<String, List<String[]>> groups = new LinkedHashMap<>();
                    while (rs.next()) {
                        String name = rs.getString("FK_NAME");
                        String key = name == null ? rs.getString("PKTABLE_NAME") + rs.getString("FKCOLUMN_NAME") : name;
                        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{
                                rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME"), rs.getString("FKCOLUMN_NAME"),
                                name, String.valueOf(rs.getShort("UPDATE_RULE")), String.valueOf(rs.getShort("DELETE_RULE"))});
                    }
                    for (List<String[]> g : groups.values()) {
                        for (String[] fk : g) {
                            Table parent = tables.get(fk[0]);
                            Column fc = t.columnByName(fk[2]);
                            Column pc = parent == null ? null : parent.columnByName(fk[1]);
                            if (parent == null || fc == null || pc == null) {
                                warnings.add(t.name + "." + fk[2] + " references " + fk[0] + " (outside this schema?)");
                                continue;
                            }
                            Relation r = new Relation(t, fc, parent, pc);
                            r.name = fk[3] == null ? "" : fk[3];
                            if (r.name.equals(SqlExporter.fkName(d, r))) r.name = "";
                            if (g.size() > 1 && r.name.isEmpty()) r.name = "fk_" + t.name + "_" + parent.name;
                            r.onUpdate = rule(Short.parseShort(fk[4]));
                            r.onDelete = rule(Short.parseShort(fk[5]));
                            d.relations.add(r);
                            if (fk[3] != null) fkNames.add(fk[3].toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            // indexes
            for (Table t : tables.values()) {
                Map<String, TableIndex> idx = new LinkedHashMap<>();
                try (ResultSet rs = md.getIndexInfo(cat, sch, t.name, false, true)) {
                    while (rs.next()) {
                        if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                        String name = rs.getString("INDEX_NAME"), col = rs.getString("COLUMN_NAME");
                        if (name == null || col == null) continue;
                        TableIndex ti = idx.computeIfAbsent(name, k -> {
                            TableIndex x = new TableIndex();
                            x.name = k;
                            return x;
                        });
                        try { ti.unique = !rs.getBoolean("NON_UNIQUE"); } catch (SQLException ignored) { }
                        Column cc = t.columnByName(col);
                        if (cc != null && !ti.columnIds.contains(cc.id)) ti.columnIds.add(cc.id);
                    }
                }
                Set<String> pkIds = new HashSet<>();
                for (Column cc : t.primaryKey()) pkIds.add(cc.id);
                for (TableIndex ti : idx.values()) {
                    if (ti.columnIds.isEmpty()) continue;
                    if (new HashSet<>(ti.columnIds).equals(pkIds)) continue; // the primary key's own index
                    if (ti.name.equalsIgnoreCase("PRIMARY")) continue;
                    if (ti.unique && ti.columnIds.size() == 1) {
                        Column cc = t.column(ti.columnIds.get(0));
                        cc.unique = true;
                        continue;
                    }
                    if (!ti.unique && fkNames.contains(ti.name.toLowerCase(Locale.ROOT))) continue; // MySQL's automatic FK index
                    t.indexes.add(ti);
                }
            }
            for (Relation r : d.relations) {
                Table f = d.table(r.fromTableId);
                Column fc = f.column(r.fromColumnId);
                boolean singlePk = f.primaryKey().size() == 1 && fc.primaryKey;
                if (fc.unique || singlePk) r.type = RelationType.ONE_TO_ONE;
            }
        }
        progress.accept("Done");
        return new ImportResult(d, warnings);
    }

    private static RefAction rule(short r) {
        return switch (r) {
            case DatabaseMetaData.importedKeyCascade -> RefAction.CASCADE;
            case DatabaseMetaData.importedKeySetNull -> RefAction.SET_NULL;
            case DatabaseMetaData.importedKeySetDefault -> RefAction.SET_DEFAULT;
            case DatabaseMetaData.importedKeyRestrict -> RefAction.RESTRICT;
            default -> RefAction.NO_ACTION;
        };
    }

    private static void pgColumns(DatabaseMetaData md, String cat, String sch, Table t) throws SQLException {
        try (ResultSet rs = md.getColumns(cat, sch, t.name, "%")) {
            while (rs.next()) {
                Column c = new Column(rs.getString("COLUMN_NAME"), "");
                String type = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                c.type = pgType(type, size, digits);
                c.notNull = rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
                String def = rs.getString("COLUMN_DEF");
                String ai = "";
                try { ai = rs.getString("IS_AUTOINCREMENT"); } catch (SQLException ignored) { }
                String tl = type == null ? "" : type.toLowerCase(Locale.ROOT);
                if ("YES".equalsIgnoreCase(ai) || tl.contains("serial") || (def != null && def.startsWith("nextval("))) {
                    c.autoIncrement = true;
                    def = null;
                }
                c.defaultValue = def == null ? "" : def;
                String remarks = rs.getString("REMARKS");
                c.comment = remarks == null ? "" : remarks;
                t.columns.add(c);
            }
        }
    }

    static String pgType(String typeName, int size, int digits) {
        String t = typeName == null ? "text" : typeName.toLowerCase(Locale.ROOT);
        boolean array = t.startsWith("_");
        if (array) t = t.substring(1);
        String base = switch (t) {
            case "int2", "smallserial" -> "SMALLINT";
            case "int4", "serial" -> "INTEGER";
            case "int8", "bigserial" -> "BIGINT";
            case "float4" -> "REAL";
            case "float8" -> "DOUBLE PRECISION";
            case "bool" -> "BOOLEAN";
            case "bpchar" -> size > 0 && size < 10485760 ? "CHAR(" + size + ")" : "CHAR";
            case "varchar" -> size > 0 && size < 10485760 ? "VARCHAR(" + size + ")" : "VARCHAR";
            case "numeric" -> size > 0 && size < 1000 ? "NUMERIC(" + size + ", " + digits + ")" : "NUMERIC";
            case "timestamptz" -> "TIMESTAMPTZ";
            case "timetz" -> "TIMETZ";
            default -> t.toUpperCase(Locale.ROOT);
        };
        return array ? base + "[]" : base;
    }

    private static void mysqlColumns(Connection con, String db, Table t) throws SQLException {
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, t.name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Column c = new Column(rs.getString(1), "");
                    String type = rs.getString(2);
                    c.type = mysqlType(type);
                    c.notNull = "NO".equalsIgnoreCase(rs.getString(3));
                    String extra = rs.getString(5) == null ? "" : rs.getString(5).toLowerCase(Locale.ROOT);
                    c.autoIncrement = extra.contains("auto_increment");
                    String def = rs.getString(4);
                    if (def != null && !c.autoIncrement) c.defaultValue = mysqlDefault(def, c.type, extra);
                    String comment = rs.getString(6);
                    c.comment = comment == null ? "" : comment;
                    t.columns.add(c);
                }
            }
        }
        // table comment (DatabaseMetaData REMARKS is unreliable on MySQL)
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, db);
            ps.setString(2, t.name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) t.comment = rs.getString(1);
            }
        }
    }

    static String mysqlType(String columnType) {
        String t = columnType == null ? "varchar(255)" : columnType.trim();
        if (t.equalsIgnoreCase("tinyint(1)")) return "BOOLEAN";
        // int(11) display widths are meaningless since MySQL 8
        t = t.replaceAll("(?i)^(tinyint|smallint|mediumint|int|integer|bigint)\\(\\d+\\)", "$1");
        if (t.equalsIgnoreCase("tinyint")) return "TINYINT";
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("enum(") || lower.startsWith("set(")) {
            int p = t.indexOf('(');
            return t.substring(0, p).toUpperCase(Locale.ROOT) + t.substring(p).replace("','", "', '");
        }
        return t.toUpperCase(Locale.ROOT).replace(",", ", ").replace(",  ", ", ");
    }

    private static String mysqlDefault(String def, String type, String extra) {
        String u = def.toUpperCase(Locale.ROOT);
        if (u.startsWith("CURRENT_TIMESTAMP") || u.equals("NOW()")) return "CURRENT_TIMESTAMP";
        if (extra.contains("default_generated")) return def.startsWith("(") ? def : "(" + def + ")";
        if (u.equals("NULL")) return "";
        if (def.startsWith("'")) return def;
        if (def.matches("-?\\d+(\\.\\d+)?") && !type.startsWith("VARCHAR") && !type.startsWith("CHAR") && !type.startsWith("ENUM")
                && !type.contains("TEXT")) return def;
        return "'" + def.replace("'", "''") + "'";
    }
}
