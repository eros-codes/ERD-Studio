package com.erdstudio.io;

import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.RefAction;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** schema.prisma <-> diagram. */
public final class PrismaIO {

    private PrismaIO() {}

    private static final Set<String> SCALARS = Set.of("String", "Boolean", "Int", "BigInt", "Float", "Decimal",
            "DateTime", "Json", "Bytes", "Unsupported");

    // ================================================================== import

    private record Attr(String name, String args) {}

    private record Field(String name, String type, boolean optional, boolean list, List<Attr> attrs, String doc) {
        Attr attr(String n) {
            for (Attr a : attrs) if (a.name.equals(n)) return a;
            return null;
        }
    }

    private record Model(String name, List<Field> fields, List<Attr> blockAttrs, String doc) {}

    public static ImportResult parse(String text) {
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> enums = new LinkedHashMap<>();
        List<Model> models = new ArrayList<>();
        Dialect dialect = Dialect.POSTGRESQL;

        String[] lines = text.replace("\r", "").split("\n");
        int i = 0;
        StringBuilder doc = new StringBuilder();
        while (i < lines.length) {
            String raw = lines[i];
            String line = stripComment(raw).trim();
            if (raw.trim().startsWith("///")) {
                doc.append(doc.length() > 0 ? "\n" : "").append(raw.trim().substring(3).trim());
                i++;
                continue;
            }
            Matcher m = Pattern.compile("^(model|enum|datasource|generator|view|type)\\s+(\\w+)\\s*\\{\\s*$").matcher(line);
            if (!m.matches()) { i++; if (!line.isEmpty()) doc.setLength(0); continue; }
            String kind = m.group(1), name = m.group(2);
            List<String> body = new ArrayList<>();
            i++;
            while (i < lines.length && !stripComment(lines[i]).trim().equals("}")) body.add(lines[i++]);
            i++;
            switch (kind) {
                case "datasource" -> {
                    for (String b : body) {
                        Matcher p = Pattern.compile("provider\\s*=\\s*\"(\\w+)\"").matcher(b);
                        if (p.find()) dialect = switch (p.group(1)) {
                            case "mysql" -> Dialect.MYSQL;
                            case "sqlite" -> Dialect.SQLITE;
                            case "sqlserver" -> Dialect.SQLSERVER;
                            default -> Dialect.POSTGRESQL;
                        };
                    }
                }
                case "enum" -> {
                    List<String> values = new ArrayList<>();
                    for (String b : body) {
                        String v = stripComment(b).trim();
                        if (v.isEmpty() || v.startsWith("@@")) continue;
                        values.add(v.split("\\s+")[0]);
                    }
                    enums.put(name, values);
                }
                case "model", "view" -> models.add(parseModel(name, body, doc.toString()));
                default -> { }
            }
            doc.setLength(0);
        }

        Diagram d = new Diagram();
        d.dialect = dialect;
        Map<String, Table> byModel = new LinkedHashMap<>();
        Map<String, Map<String, Column>> fieldCols = new HashMap<>();
        Set<String> modelNames = new HashSet<>();
        for (Model m : models) modelNames.add(m.name);

        for (Model m : models) {
            Table t = new Table(mapped(m.blockAttrs, m.name));
            t.comment = m.doc;
            Map<String, Column> cols = new LinkedHashMap<>();
            for (Field f : m.fields) {
                if (modelNames.contains(f.type) || f.list && !SCALARS.contains(f.type) && !enums.containsKey(f.type)) continue;
                Column c = new Column(mapped(f.attrs, f.name), "");
                c.type = sqlType(f, enums, dialect);
                c.notNull = !f.optional;
                c.comment = f.doc;
                if (f.attr("id") != null) { c.primaryKey = true; c.notNull = true; }
                if (f.attr("unique") != null) c.unique = true;
                Attr def = f.attr("default");
                if (def != null) applyDefault(c, def.args, f.type, enums.containsKey(f.type));
                if (f.attr("updatedAt") != null && c.comment.isBlank()) c.comment = "@updatedAt";
                t.columns.add(c);
                cols.put(f.name, c);
            }
            for (Attr a : m.blockAttrs) {
                List<String> names = fieldList(a.args);
                switch (a.name) {
                    case "id" -> { for (String n : names) { Column c = cols.get(n); if (c != null) { c.primaryKey = true; c.notNull = true; } } }
                    case "unique", "index" -> {
                        TableIndex idx = new TableIndex();
                        idx.unique = a.name.equals("unique");
                        Matcher nm = Pattern.compile("(?:map|name)\\s*:\\s*\"([^\"]+)\"").matcher(a.args);
                        if (nm.find()) idx.name = nm.group(1);
                        for (String n : names) { Column c = cols.get(n); if (c != null) idx.columnIds.add(c.id); }
                        if (idx.unique && idx.columnIds.size() == 1 && idx.name.isEmpty()) t.column(idx.columnIds.get(0)).unique = true;
                        else if (!idx.columnIds.isEmpty()) t.indexes.add(idx);
                    }
                    default -> { }
                }
            }
            d.tables.add(t);
            byModel.put(m.name, t);
            fieldCols.put(m.name, cols);
        }

        // relations: fields with @relation(fields: [...], references: [...])
        for (Model m : models) {
            Table child = byModel.get(m.name);
            for (Field f : m.fields) {
                Attr rel = f.attr("relation");
                if (rel == null || !byModel.containsKey(f.type)) continue;
                List<String> fs = namedList(rel.args, "fields"), rs = namedList(rel.args, "references");
                if (fs.isEmpty() || fs.size() != rs.size()) continue;
                Table parent = byModel.get(f.type);
                String relName = quotedFirst(rel.args);
                for (int k = 0; k < fs.size(); k++) {
                    Column fc = fieldCols.get(m.name).get(fs.get(k));
                    Column tc = fieldCols.get(f.type).get(rs.get(k));
                    if (fc == null || tc == null) { warnings.add(m.name + "." + f.name + ": relation field not found"); continue; }
                    Relation r = new Relation(child, fc, parent, tc);
                    Matcher od = Pattern.compile("onDelete\\s*:\\s*(\\w+)").matcher(rel.args);
                    Matcher ou = Pattern.compile("onUpdate\\s*:\\s*(\\w+)").matcher(rel.args);
                    if (od.find()) r.onDelete = action(od.group(1));
                    if (ou.find()) r.onUpdate = action(ou.group(1));
                    Matcher mp = Pattern.compile("map\\s*:\\s*\"([^\"]+)\"").matcher(rel.args);
                    if (mp.find()) r.name = mp.group(1);
                    else if (fs.size() > 1) r.name = "fk_" + child.name + "_" + (relName == null ? f.name : relName);
                    r.type = fs.size() == 1 && (fc.unique || fc.primaryKey) ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
                    d.relations.add(r);
                }
            }
        }
        return new ImportResult(d, warnings);
    }

    private static Model parseModel(String name, List<String> body, String doc) {
        List<Field> fields = new ArrayList<>();
        List<Attr> block = new ArrayList<>();
        StringBuilder fieldDoc = new StringBuilder();
        for (String raw : body) {
            String t = raw.trim();
            if (t.startsWith("///")) { fieldDoc.append(fieldDoc.length() > 0 ? "\n" : "").append(t.substring(3).trim()); continue; }
            String line = stripComment(raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("@@")) { block.addAll(attrs(line.substring(1))); fieldDoc.setLength(0); continue; }
            Matcher m = Pattern.compile("^(\\w+)\\s+([\\w.]+(?:\\([^)]*\\))?)(\\[\\])?(\\?)?\\s*(.*)$").matcher(line);
            if (!m.matches()) continue;
            String type = m.group(2);
            if (type.startsWith("Unsupported(")) type = "Unsupported:" + type.substring(12, type.length() - 1).replace("\"", "");
            fields.add(new Field(m.group(1), type, m.group(4) != null, m.group(3) != null, attrs(m.group(5)), fieldDoc.toString()));
            fieldDoc.setLength(0);
        }
        return new Model(name, fields, block, doc);
    }

    /** "@id @default(autoincrement()) @db.VarChar(255)" -> attrs (nested parentheses aware). */
    private static List<Attr> attrs(String s) {
        List<Attr> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            if (s.charAt(i) != '@') { i++; continue; }
            int start = ++i;
            while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) i++;
            String name = s.substring(start, i);
            String args = "";
            if (i < n && s.charAt(i) == '(') {
                int depth = 0, a = i;
                boolean str = false;
                for (; i < n; i++) {
                    char c = s.charAt(i);
                    if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) str = !str;
                    if (str) continue;
                    if (c == '(') depth++;
                    if (c == ')' && --depth == 0) { i++; break; }
                }
                args = s.substring(a + 1, Math.max(a + 1, i - 1));
            }
            out.add(new Attr(name, args));
        }
        return out;
    }

    private static String stripComment(String line) {
        boolean str = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"') str = !str;
            if (!str && c == '/' && line.charAt(i + 1) == '/') return line.substring(0, i);
        }
        return line;
    }

    private static String mapped(List<Attr> attrs, String fallback) {
        for (Attr a : attrs) {
            if (a.name.equals("map")) {
                String q = quotedFirst(a.args);
                if (q != null) return q;
            }
        }
        return fallback;
    }

    private static String quotedFirst(String s) {
        Matcher m = Pattern.compile("^\\s*(?:name\\s*:\\s*)?\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** "[a, b]" or "fields: [a, b]" -> [a, b] (first bracket list) */
    private static List<String> fieldList(String args) {
        Matcher m = Pattern.compile("\\[([^\\]]*)\\]").matcher(args);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            for (String p : m.group(1).split(",")) {
                String v = p.trim().replaceAll("\\(.*\\)$", "").trim();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    private static List<String> namedList(String args, String key) {
        Matcher m = Pattern.compile(key + "\\s*:\\s*\\[([^\\]]*)\\]").matcher(args);
        List<String> out = new ArrayList<>();
        if (m.find()) for (String p : m.group(1).split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private static RefAction action(String s) {
        return switch (s) {
            case "Cascade" -> RefAction.CASCADE;
            case "Restrict" -> RefAction.RESTRICT;
            case "SetNull" -> RefAction.SET_NULL;
            case "SetDefault" -> RefAction.SET_DEFAULT;
            default -> RefAction.NO_ACTION;
        };
    }

    private static String sqlType(Field f, Map<String, List<String>> enums, Dialect dl) {
        Attr nat = null;
        for (Attr a : f.attrs) if (a.name.startsWith("db.")) nat = a;
        String base;
        if (nat != null) {
            String n = nat.name.substring(3);
            base = switch (n) {
                case "Timestamptz" -> "TIMESTAMPTZ";
                case "Timestamp" -> "TIMESTAMP";
                case "DoublePrecision" -> "DOUBLE PRECISION";
                case "VarChar", "NVarChar", "Char", "NChar", "Decimal", "Numeric", "VarBinary", "Binary" ->
                        n.toUpperCase(Locale.ROOT) + (nat.args.isBlank() ? "" : "(" + nat.args.replace(" ", "").replace(",", ", ") + ")");
                default -> n.toUpperCase(Locale.ROOT);
            };
            return base;
        }
        if (f.type.startsWith("Unsupported:")) return f.type.substring(12);
        if (enums.containsKey(f.type)) {
            List<String> v = enums.get(f.type);
            StringBuilder sb = new StringBuilder("ENUM(");
            for (int i = 0; i < v.size(); i++) sb.append(i > 0 ? ", " : "").append('\'').append(v.get(i)).append('\'');
            return sb.append(')').toString();
        }
        String t = switch (f.type) {
            case "String" -> dl == Dialect.MYSQL ? "VARCHAR(191)" : dl == Dialect.SQLSERVER ? "NVARCHAR(1000)" : "TEXT";
            case "Boolean" -> dl == Dialect.SQLSERVER ? "BIT" : "BOOLEAN";
            case "Int" -> "INTEGER";
            case "BigInt" -> "BIGINT";
            case "Float" -> "DOUBLE PRECISION";
            case "Decimal" -> "DECIMAL(65, 30)";
            case "DateTime" -> dl == Dialect.POSTGRESQL ? "TIMESTAMP(3)" : dl == Dialect.SQLSERVER ? "DATETIME2" : "DATETIME(3)";
            case "Json" -> dl == Dialect.POSTGRESQL ? "JSONB" : "JSON";
            case "Bytes" -> dl == Dialect.POSTGRESQL ? "BYTEA" : "BLOB";
            default -> "TEXT";
        };
        return f.list ? t + "[]" : t;
    }

    private static void applyDefault(Column c, String args, String prismaType, boolean isEnum) {
        String a = args.trim();
        if (a.equals("autoincrement()")) { c.autoIncrement = true; return; }
        if (a.equals("now()")) { c.defaultValue = "now()"; return; }
        if (a.equals("uuid()") || a.startsWith("uuid(")) { c.defaultValue = "gen_random_uuid()"; return; }
        if (a.startsWith("cuid(") || a.startsWith("nanoid(") || a.startsWith("ulid(")) { c.comment = c.comment.isBlank() ? "@default(" + a + ")" : c.comment; return; }
        Matcher db = Pattern.compile("^dbgenerated\\(\"(.*)\"\\)$").matcher(a);
        if (db.matches()) { c.defaultValue = db.group(1).replace("\\\"", "\""); return; }
        if (a.startsWith("\"")) { c.defaultValue = "'" + a.substring(1, a.length() - 1).replace("'", "''") + "'"; return; }
        if (isEnum) { c.defaultValue = "'" + a + "'"; return; }
        c.defaultValue = a;
    }

    // ================================================================== export

    public record Options(boolean prismaNaming, Dialect provider) {}

    public static String export(Diagram d, Options opt) {
        Dialect dl = opt.provider();
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(d.name).append(" — generated by ERD Studio\n\n");
        sb.append("generator client {\n  provider = \"prisma-client-js\"\n}\n\n");
        sb.append("datasource db {\n  provider = \"").append(switch (dl) {
            case POSTGRESQL -> "postgresql";
            case MYSQL -> "mysql";
            case SQLITE -> "sqlite";
            case SQLSERVER -> "sqlserver";
        }).append("\"\n  url      = env(\"DATABASE_URL\")\n}\n");

        // model and field names
        Map<String, String> modelName = new LinkedHashMap<>();
        Set<String> usedModels = new HashSet<>();
        for (Table t : d.tables) {
            String n = opt.prismaNaming() ? pascal(singular(t.name)) : ident(t.name);
            String u = n;
            int k = 2;
            while (!usedModels.add(u)) u = n + k++;
            modelName.put(t.id, u);
        }
        Map<String, String> fieldName = new HashMap<>(); // column id -> field
        Map<String, Set<String>> usedFields = new HashMap<>();
        for (Table t : d.tables) {
            Set<String> used = new HashSet<>();
            usedFields.put(t.id, used);
            for (Column c : t.columns) {
                String n = opt.prismaNaming() ? camel(c.name) : ident(c.name);
                String u = n;
                int k = 2;
                while (!used.add(u)) u = n + k++;
                fieldName.put(c.id, u);
            }
        }

        // enums generated from ENUM('a','b') column types
        Map<String, String> enumFor = new HashMap<>(); // column id -> enum name
        StringBuilder enumBlocks = new StringBuilder();
        Pattern enumP = Pattern.compile("(?i)^\\s*ENUM\\s*\\((.*)\\)\\s*$");
        for (Table t : d.tables) {
            for (Column c : t.columns) {
                Matcher m = enumP.matcher(c.type == null ? "" : c.type);
                if (!m.matches()) continue;
                String en = modelName.get(t.id) + pascal(c.name);
                enumFor.put(c.id, en);
                enumBlocks.append("\nenum ").append(en).append(" {\n");
                for (String v : m.group(1).split(",")) {
                    String val = v.trim().replaceAll("^'|'$", "");
                    enumBlocks.append("  ").append(ident(val)).append('\n');
                }
                enumBlocks.append("}\n");
            }
        }

        // relation fields
        Map<String, List<String>> extra = new LinkedHashMap<>();
        for (Table t : d.tables) extra.put(t.id, new ArrayList<>());
        Map<String, Integer> pairCount = new HashMap<>();
        for (List<Relation> g : SqlExporter.groupForeignKeys(d).values()) {
            Relation r = g.get(0);
            String key = r.fromTableId.compareTo(r.toTableId) < 0 ? r.fromTableId + r.toTableId : r.toTableId + r.fromTableId;
            pairCount.merge(key, 1, Integer::sum);
        }
        for (List<Relation> g : SqlExporter.groupForeignKeys(d).values()) {
            Relation r = g.get(0);
            Table child = d.table(r.fromTableId), parent = d.table(r.toTableId);
            String key = r.fromTableId.compareTo(r.toTableId) < 0 ? r.fromTableId + r.toTableId : r.toTableId + r.fromTableId;
            boolean needName = pairCount.get(key) > 1 || child.id.equals(parent.id);
            Column fc = child.column(r.fromColumnId);
            String base = fc.name.replaceAll("(?i)_?id$", "");
            if (base.isEmpty()) base = parent.name;
            String fwd = uniqueField(usedFields.get(child.id), opt.prismaNaming() ? camel(base) : ident(base));
            String back = uniqueField(usedFields.get(parent.id), opt.prismaNaming()
                    ? camel(r.type == RelationType.ONE_TO_ONE ? singular(child.name) : plural(singular(child.name)))
                    : ident(child.name) + (needName ? "_" + ident(fc.name) : ""));
            String relName = needName ? "\"" + modelName.get(child.id) + "_" + fieldName.get(fc.id) + "\", " : "";
            List<String> fs = new ArrayList<>(), rs = new ArrayList<>();
            boolean optional = false;
            for (Relation x : g) {
                fs.add(fieldName.get(x.fromColumnId));
                rs.add(fieldName.get(x.toColumnId));
                if (!child.column(x.fromColumnId).isRequired()) optional = true;
            }
            StringBuilder f = new StringBuilder("  ").append(fwd).append(' ').append(modelName.get(parent.id)).append(optional ? "?" : "")
                    .append(" @relation(").append(relName).append("fields: [").append(String.join(", ", fs))
                    .append("], references: [").append(String.join(", ", rs)).append("]");
            if (r.onDelete != null && r.onDelete != RefAction.NO_ACTION) f.append(", onDelete: ").append(prismaAction(r.onDelete));
            if (r.onUpdate != null && r.onUpdate != RefAction.NO_ACTION) f.append(", onUpdate: ").append(prismaAction(r.onUpdate));
            if (r.name != null && !r.name.isBlank()) f.append(", map: \"").append(r.name).append('"');
            f.append(')');
            extra.get(child.id).add(f.toString());
            String backType = modelName.get(child.id) + (r.type == RelationType.ONE_TO_ONE ? "?" : "[]");
            extra.get(parent.id).add("  " + back + " " + backType + (needName ? " @relation(" + relName.replaceAll(",\\s*$", "") + ")" : ""));
        }
        Set<String> oneToOneFk = new HashSet<>();
        for (Relation r : d.relations) if (r.type == RelationType.ONE_TO_ONE) oneToOneFk.add(r.fromColumnId);

        for (Table t : d.tables) {
            sb.append('\n');
            if (!t.comment.isBlank()) for (String l : t.comment.split("\\R")) sb.append("/// ").append(l).append('\n');
            sb.append("model ").append(modelName.get(t.id)).append(" {\n");
            List<Column> pk = t.primaryKey();
            int w = 1, tw = 1;
            for (Column c : t.columns) {
                w = Math.max(w, fieldName.get(c.id).length());
                tw = Math.max(tw, prismaType(c, dl, enumFor).length() + 1);
            }
            for (Column c : t.columns) {
                if (!c.comment.isBlank() && !c.comment.startsWith("@")) for (String l : c.comment.split("\\R")) sb.append("  /// ").append(l).append('\n');
                String fname = fieldName.get(c.id);
                String type = prismaType(c, dl, enumFor) + (c.isRequired() ? "" : "?");
                sb.append("  ").append(pad(fname, w)).append(' ').append(pad(type, tw));
                List<String> at = new ArrayList<>();
                if (c.primaryKey && pk.size() == 1) at.add("@id");
                if ((c.unique && !c.primaryKey) || (oneToOneFk.contains(c.id) && !c.primaryKey && !c.unique)) at.add("@unique");
                String def = prismaDefault(c, enumFor.containsKey(c.id));
                if (def != null) at.add("@default(" + def + ")");
                if ("@updatedAt".equals(c.comment)) at.add("@updatedAt");
                if (!fname.equals(c.name)) at.add("@map(\"" + c.name + "\")");
                String nat = nativeType(c, dl);
                if (nat != null && !enumFor.containsKey(c.id)) at.add(nat);
                if (!at.isEmpty()) sb.append(' ').append(String.join(" ", at));
                sb.append('\n');
            }
            List<String> rels = extra.get(t.id);
            if (!rels.isEmpty()) {
                sb.append('\n');
                for (String r : rels) sb.append(r).append('\n');
            }
            boolean blank = false;
            if (pk.size() > 1) {
                List<String> ids = new ArrayList<>();
                for (Column c : pk) ids.add(fieldName.get(c.id));
                if (!blank) { sb.append('\n'); blank = true; }
                sb.append("  @@id([").append(String.join(", ", ids)).append("])\n");
            }
            for (TableIndex idx : t.indexes) {
                List<String> cols = new ArrayList<>();
                for (String cid : idx.columnIds) if (fieldName.containsKey(cid) && t.column(cid) != null) cols.add(fieldName.get(cid));
                if (cols.isEmpty()) continue;
                if (!blank) { sb.append('\n'); blank = true; }
                sb.append("  @@").append(idx.unique ? "unique" : "index").append("([").append(String.join(", ", cols)).append("]");
                if (idx.name != null && !idx.name.isBlank()) sb.append(", map: \"").append(idx.name).append('"');
                sb.append(")\n");
            }
            if (!modelName.get(t.id).equals(t.name)) {
                if (!blank) sb.append('\n');
                sb.append("  @@map(\"").append(t.name).append("\")\n");
            }
            sb.append("}\n");
        }
        sb.append(enumBlocks);
        return sb.toString();
    }

    private static String uniqueField(Set<String> used, String base) {
        String u = base;
        int k = 2;
        while (!used.add(u)) u = base + k++;
        return u;
    }

    private static String prismaAction(RefAction a) {
        return switch (a) {
            case CASCADE -> "Cascade";
            case RESTRICT -> "Restrict";
            case SET_NULL -> "SetNull";
            case SET_DEFAULT -> "SetDefault";
            case NO_ACTION -> "NoAction";
        };
    }

    private static String baseOf(String type) {
        return type == null ? "" : type.toUpperCase(Locale.ROOT).replaceAll("\\(.*", "").replace("[]", "").trim();
    }

    private static String prismaType(Column c, Dialect dl, Map<String, String> enumFor) {
        if (enumFor.containsKey(c.id)) return enumFor.get(c.id);
        String u = c.type == null ? "" : c.type.toUpperCase(Locale.ROOT).trim();
        if (u.endsWith("[]")) {
            Column inner = c.copy(false);
            inner.type = c.type.substring(0, c.type.length() - 2);
            return prismaType(inner, dl, enumFor) + "[]";
        }
        if (u.equals("TINYINT(1)") || u.equals("BIT")) return "Boolean";
        String b = baseOf(c.type);
        return switch (b) {
            case "INT", "INTEGER", "INT4", "SMALLINT", "INT2", "TINYINT", "MEDIUMINT", "SERIAL", "SMALLSERIAL", "INT UNSIGNED", "YEAR" -> "Int";
            case "BIGINT", "INT8", "BIGSERIAL", "BIGINT UNSIGNED" -> "BigInt";
            case "BOOLEAN", "BOOL" -> "Boolean";
            case "REAL", "FLOAT", "FLOAT4", "FLOAT8", "DOUBLE", "DOUBLE PRECISION" -> "Float";
            case "DECIMAL", "NUMERIC", "MONEY", "NUMBER" -> "Decimal";
            case "DATE", "TIME", "TIMETZ", "TIMESTAMP", "TIMESTAMPTZ", "DATETIME", "DATETIME2", "DATETIMEOFFSET", "SMALLDATETIME",
                 "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITHOUT TIME ZONE" -> "DateTime";
            case "JSON", "JSONB" -> "Json";
            case "BYTEA", "BLOB", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "VARBINARY", "BINARY", "IMAGE" -> "Bytes";
            case "VARCHAR", "NVARCHAR", "CHAR", "NCHAR", "TEXT", "MEDIUMTEXT", "LONGTEXT", "TINYTEXT", "NTEXT", "UUID",
                 "UNIQUEIDENTIFIER", "CHARACTER VARYING", "CHARACTER", "CITEXT", "XML", "INET", "CLOB" -> "String";
            default -> "Unsupported(\"" + c.type.replace("\"", "'") + "\")";
        };
    }

    /** @db.* attribute where the SQL type is more specific than Prisma's default. */
    private static String nativeType(Column c, Dialect dl) {
        if (dl == Dialect.SQLITE || c.type == null) return null;
        String u = c.type.toUpperCase(Locale.ROOT).trim();
        Matcher m = Pattern.compile("^([A-Z ]+?)\\s*\\(([^)]*)\\)$").matcher(u);
        String b = m.matches() ? m.group(1).trim() : u;
        String args = m.matches() ? m.group(2).replace(" ", "").replace(",", ", ") : "";
        return switch (b) {
            case "VARCHAR", "CHARACTER VARYING" -> args.isEmpty() ? null : dl == Dialect.SQLSERVER ? "@db.VarChar(" + args + ")" : "@db.VarChar(" + args + ")";
            case "NVARCHAR" -> dl == Dialect.SQLSERVER ? "@db.NVarChar(" + (args.isEmpty() ? "1000" : args) + ")" : args.isEmpty() ? null : "@db.VarChar(" + args + ")";
            case "CHAR", "CHARACTER" -> args.isEmpty() ? null : "@db.Char(" + args + ")";
            case "TEXT" -> dl == Dialect.SQLSERVER ? "@db.NVarChar(Max)" : "@db.Text";
            case "UUID" -> dl == Dialect.POSTGRESQL ? "@db.Uuid" : null;
            case "UNIQUEIDENTIFIER" -> dl == Dialect.SQLSERVER ? "@db.UniqueIdentifier" : null;
            case "DECIMAL", "NUMERIC" -> args.isEmpty() ? null : "@db.Decimal(" + args + ")";
            case "SMALLINT" -> "@db.SmallInt";
            case "TINYINT" -> dl == Dialect.POSTGRESQL ? "@db.SmallInt" : "@db.TinyInt";
            case "REAL" -> dl == Dialect.POSTGRESQL ? "@db.Real" : null;
            case "DATE" -> "@db.Date";
            case "TIME" -> "@db.Time";
            case "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> dl == Dialect.POSTGRESQL ? "@db.Timestamptz(6)" : null;
            case "JSONB" -> null;
            case "JSON" -> dl == Dialect.POSTGRESQL ? "@db.Json" : null;
            case "MEDIUMTEXT" -> dl == Dialect.MYSQL ? "@db.MediumText" : null;
            case "LONGTEXT" -> dl == Dialect.MYSQL ? "@db.LongText" : null;
            default -> null;
        };
    }

    private static String prismaDefault(Column c, boolean isEnum) {
        if (c.autoIncrement) return "autoincrement()";
        String v = c.defaultValue == null ? "" : c.defaultValue.trim();
        if (v.isEmpty()) return null;
        String u = v.toUpperCase(Locale.ROOT).replace(" ", "");
        if (u.equals("NOW()") || u.startsWith("CURRENT_TIMESTAMP") || u.equals("GETDATE()") || u.equals("SYSDATETIME()")) return "now()";
        if (u.contains("UUID") || u.contains("NEWID")) return "uuid()";
        String stripped = v.replaceAll("::[\\w\\s]+$", "").trim();
        if (stripped.startsWith("'") && stripped.endsWith("'") && stripped.length() >= 2) {
            String s = stripped.substring(1, stripped.length() - 1).replace("''", "'");
            return isEnum ? ident(s) : "\"" + s.replace("\"", "\\\"") + "\"";
        }
        if (stripped.matches("-?\\d+(\\.\\d+)?") || stripped.equalsIgnoreCase("true") || stripped.equalsIgnoreCase("false")) {
            return stripped.toLowerCase(Locale.ROOT);
        }
        return "dbgenerated(\"" + v.replace("\"", "\\\"") + "\")";
    }

    // ------------------------------------------------------------------ naming

    static String ident(String s) {
        String v = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (v.isEmpty() || Character.isDigit(v.charAt(0))) v = "_" + v;
        return v;
    }

    static String pascal(String s) {
        StringBuilder sb = new StringBuilder();
        for (String p : s.split("[^A-Za-z0-9]+")) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        String r = sb.toString();
        if (r.isEmpty() || Character.isDigit(r.charAt(0))) r = "M" + r;
        return r;
    }

    static String camel(String s) {
        String p = pascal(s);
        if (p.length() > 1 && Character.isUpperCase(p.charAt(1)) && p.equals(p.toUpperCase(Locale.ROOT))) return p.toLowerCase(Locale.ROOT);
        return Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    static String singular(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith("ies") && n.length() > 4) return n.substring(0, n.length() - 3) + "y";
        if (l.endsWith("sses") || l.endsWith("xes") || l.endsWith("ches") || l.endsWith("shes")) return n.substring(0, n.length() - 2);
        if (l.endsWith("s") && !l.endsWith("ss") && n.length() > 3) return n.substring(0, n.length() - 1);
        return n;
    }

    static String plural(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith("y") && n.length() > 2 && "aeiou".indexOf(l.charAt(l.length() - 2)) < 0) return n.substring(0, n.length() - 1) + "ies";
        if (l.endsWith("s") || l.endsWith("x") || l.endsWith("ch") || l.endsWith("sh")) return n + "es";
        return n + "s";
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
