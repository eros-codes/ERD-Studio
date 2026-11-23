package com.erdstudio.io;

import com.erdstudio.io.SqlLexer.Kind;
import com.erdstudio.io.SqlLexer.Token;
import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.Palette;
import com.erdstudio.model.RefAction;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns DDL (CREATE TABLE / ALTER TABLE / CREATE INDEX / COMMENT ON) into a diagram.
 * Deliberately tolerant: anything it doesn't understand is skipped, never fatal.
 */
public final class SqlImporter {

    private record PendingFk(String name, Table from, List<String> fromCols, String toTable, List<String> toCols,
                             RefAction onDelete, RefAction onUpdate, int line) {}

    private static final Set<String> COLUMN_STOP = Set.of(
            "CONSTRAINT", "NOT", "NULL", "PRIMARY", "UNIQUE", "DEFAULT", "REFERENCES", "CHECK", "AUTO_INCREMENT",
            "AUTOINCREMENT", "IDENTITY", "GENERATED", "COLLATE", "COMMENT", "ON", "CHARACTER", "CHARSET", "KEY", "AS",
            "STORED", "VIRTUAL", "VISIBLE", "INVISIBLE", "ROWGUIDCOL", "SPARSE", "FILESTREAM", "MASKED");

    private final Dialect dialect;
    private final Diagram diagram = new Diagram();
    private final Map<String, Table> byName = new LinkedHashMap<>();
    private final List<PendingFk> pending = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private SqlImporter(Dialect dialect) {
        this.dialect = dialect;
        diagram.dialect = dialect;
    }

    public static ImportResult parse(String sql, Dialect dialect) {
        SqlImporter imp = new SqlImporter(dialect);
        imp.run(sql);
        return new ImportResult(imp.diagram, imp.warnings);
    }

    private void run(String sql) {
        for (List<Token> stmt : SqlLexer.statements(SqlLexer.tokenize(sql))) {
            Cursor c = new Cursor(stmt);
            try {
                statement(c);
            } catch (RuntimeException e) {
                warnings.add("Line " + stmt.get(0).line() + ": skipped statement (" + e.getMessage() + ")");
            }
        }
        resolveForeignKeys();
        int i = 0;
        for (Table t : diagram.tables) t.color = Palette.tableColor(i++);
    }

    // ------------------------------------------------------------------ statements

    private void statement(Cursor c) {
        if (c.at("CREATE")) {
            int save = c.pos;
            c.next();
            c.acceptSeq("OR", "REPLACE");
            while (c.atAny("TEMP", "TEMPORARY", "UNLOGGED", "GLOBAL", "LOCAL", "TRANSIENT")) c.next();
            if (c.accept("TABLE")) { createTable(c); return; }
            c.pos = save;
            c.next();
            boolean unique = c.accept("UNIQUE");
            while (c.atAny("CLUSTERED", "NONCLUSTERED", "FULLTEXT", "SPATIAL", "BITMAP")) c.next();
            if (c.accept("INDEX")) { createIndex(c, unique); return; }
            return; // CREATE VIEW / TYPE / SEQUENCE / DATABASE ... ignored
        }
        if (c.at("ALTER")) {
            c.next();
            if (!c.accept("TABLE")) return;
            alterTable(c);
            return;
        }
        if (c.at("COMMENT")) {
            c.next();
            if (c.accept("ON")) commentOn(c);
        }
    }

    private void createTable(Cursor c) {
        c.acceptSeq("IF", "NOT", "EXISTS");
        String name = c.qualifiedName();
        if (!c.atSym("(")) {
            warnings.add("Line " + c.line() + ": table '" + name + "' has no column list (CREATE TABLE ... AS?), skipped");
            return;
        }
        Table t = byName.get(key(name));
        if (t == null) {
            t = new Table(name);
            byName.put(key(name), t);
            diagram.tables.add(t);
        } else {
            warnings.add("Line " + c.line() + ": table '" + name + "' defined twice; columns were merged");
        }
        for (Cursor item : c.groupItems()) {
            if (item.eof()) continue;
            tableItem(item, t);
        }
        // trailing table options, e.g. MySQL: ENGINE=InnoDB COMMENT='...'
        while (!c.eof()) {
            if (c.accept("COMMENT")) {
                c.acceptSym("=");
                if (c.peek().kind() == Kind.STRING) t.comment = c.next().text();
            } else {
                c.next();
            }
        }
    }

    private void alterTable(Cursor c) {
        c.accept("ONLY");
        c.acceptSeq("IF", "EXISTS");
        String name = c.qualifiedName();
        Table t = byName.get(key(name));
        if (t == null) {
            warnings.add("Line " + c.line() + ": ALTER TABLE on unknown table '" + name + "'");
            return;
        }
        for (Cursor action : c.restItems()) {
            if (action.atAny("WITH")) { action.next(); action.next(); } // SQL Server: WITH CHECK / NOCHECK
            if (!action.accept("ADD")) continue;
            if (action.accept("COLUMN")) {
                action.acceptSeq("IF", "NOT", "EXISTS");
                columnDef(action, t);
            } else {
                tableItem(action, t);
            }
        }
    }

    private void createIndex(Cursor c, boolean unique) {
        c.accept("CONCURRENTLY");
        c.acceptSeq("IF", "NOT", "EXISTS");
        String idxName = "";
        if (!c.at("ON")) idxName = c.qualifiedName();
        if (!c.accept("ON")) return;
        c.accept("ONLY");
        String table = c.qualifiedName();
        if (c.accept("USING")) c.next();
        Table t = byName.get(key(table));
        if (t == null) {
            warnings.add("Line " + c.line() + ": index on unknown table '" + table + "'");
            return;
        }
        addIndex(t, idxName, unique, c.nameList());
    }

    private void commentOn(Cursor c) {
        if (c.accept("TABLE")) {
            List<String> parts = c.nameParts();
            if (!c.accept("IS")) return;
            Table t = byName.get(key(parts.get(parts.size() - 1)));
            if (t != null && c.peek().kind() == Kind.STRING) t.comment = c.next().text();
        } else if (c.accept("COLUMN")) {
            List<String> parts = c.nameParts();
            if (!c.accept("IS") || parts.size() < 2) return;
            Table t = byName.get(key(parts.get(parts.size() - 2)));
            Column col = t == null ? null : t.columnByName(parts.get(parts.size() - 1));
            if (col != null && c.peek().kind() == Kind.STRING) col.comment = c.next().text();
        }
    }

    // ------------------------------------------------------------------ table items

    private void tableItem(Cursor c, Table t) {
        if (c.accept("CONSTRAINT")) {
            if (c.peek().isIdentifier() && !c.atAny("PRIMARY", "UNIQUE", "FOREIGN", "CHECK")) c.next();
        }
        if (c.acceptSeq("PRIMARY", "KEY")) {
            while (c.atAny("CLUSTERED", "NONCLUSTERED")) c.next();
            for (String colName : c.nameList()) {
                Column col = t.columnByName(colName);
                if (col != null) { col.primaryKey = true; col.notNull = true; }
                else warnings.add("Line " + c.line() + ": primary key column '" + colName + "' not found in " + t.name);
            }
            return;
        }
        if (c.at("UNIQUE")) {
            c.next();
            if (!c.accept("KEY")) c.accept("INDEX");
            while (c.atAny("CLUSTERED", "NONCLUSTERED")) c.next();
            String name = c.atSym("(") ? "" : c.qualifiedName();
            List<String> cols = c.nameList();
            if (cols.size() == 1) {
                Column col = t.columnByName(cols.get(0));
                if (col != null) { col.unique = true; return; }
            }
            addIndex(t, name, true, cols);
            return;
        }
        if (c.acceptSeq("FOREIGN", "KEY")) {
            String name = c.atSym("(") ? "" : c.qualifiedName();
            List<String> cols = c.nameList();
            if (c.accept("REFERENCES")) references(c, t, name, cols);
            return;
        }
        if (c.atAny("FULLTEXT", "SPATIAL")) {
            c.next();
            if (!c.accept("KEY")) c.accept("INDEX");
            String name = c.atSym("(") ? "" : c.qualifiedName();
            addIndex(t, name, false, c.nameList());
            return;
        }
        if (c.atAny("KEY", "INDEX") && (c.peek(1).isIdentifier() || c.peek(1).sym("("))) {
            c.next();
            String name = c.atSym("(") ? "" : c.qualifiedName();
            addIndex(t, name, false, c.nameList());
            return;
        }
        if (c.atAny("CHECK", "EXCLUDE", "PERIOD", "LIKE")) return;
        columnDef(c, t);
    }

    private void columnDef(Cursor c, Table t) {
        if (!c.peek().isIdentifier()) return;
        Column col = new Column(c.next().text(), "");
        StringBuilder type = new StringBuilder();
        while (!c.eof() && !(c.peek().kind() == Kind.WORD && COLUMN_STOP.contains(upper(c.peek().text())))) {
            Token tok = c.peek();
            if (tok.sym("(")) {
                type.append(c.groupText(true));
                continue;
            }
            c.next();
            if (tok.kind() == Kind.QUOTED && tok.text().isEmpty()) { type.append("[]"); continue; }
            if (tok.sym("[")) { type.append("["); continue; }
            if (tok.sym("]")) { type.append("]"); continue; }
            if (tok.sym(",")) break;
            if (type.length() > 0 && !type.toString().endsWith("(")) type.append(' ');
            type.append(tok.isIdentifier() ? upper(tok.text()) : tok.text());
        }
        col.type = type.length() == 0 ? dialect.defaultTextType() : type.toString().trim();
        normalizeSerial(col);

        while (!c.eof()) {
            if (c.accept("CONSTRAINT")) {
                if (c.peek().isIdentifier() && !COLUMN_STOP.contains(upper(c.peek().text()))) c.next();
            } else if (c.acceptSeq("NOT", "NULL")) {
                col.notNull = true;
            } else if (c.accept("NULL")) {
                col.notNull = false;
            } else if (c.acceptSeq("PRIMARY", "KEY")) {
                col.primaryKey = true;
                while (c.atAny("ASC", "DESC", "CLUSTERED", "NONCLUSTERED")) c.next();
                if (c.accept("AUTOINCREMENT")) col.autoIncrement = true;
            } else if (c.accept("UNIQUE")) {
                c.accept("KEY");
                col.unique = true;
            } else if (c.accept("KEY")) {
                col.primaryKey = true;
            } else if (c.accept("DEFAULT")) {
                col.defaultValue = c.expression();
            } else if (c.atAny("AUTO_INCREMENT", "AUTOINCREMENT")) {
                c.next();
                col.autoIncrement = true;
            } else if (c.accept("IDENTITY")) {
                col.autoIncrement = true;
                if (c.atSym("(")) c.groupText(false);
            } else if (c.accept("GENERATED")) {
                if (!c.accept("ALWAYS")) c.acceptSeq("BY", "DEFAULT");
                c.acceptSeq("ON", "NULL");
                if (c.accept("AS")) {
                    if (c.accept("IDENTITY")) {
                        col.autoIncrement = true;
                        if (c.atSym("(")) c.groupText(false);
                    } else if (c.atSym("(")) {
                        c.groupText(false);
                    }
                }
            } else if (c.accept("REFERENCES")) {
                references(c, t, "", List.of(col.name));
            } else if (c.accept("CHECK")) {
                if (c.atSym("(")) c.groupText(false);
            } else if (c.accept("COLLATE")) {
                c.next();
            } else if (c.accept("COMMENT")) {
                if (c.peek().kind() == Kind.STRING) col.comment = c.next().text();
            } else if (c.acceptSeq("ON", "UPDATE")) {
                c.expression();
            } else if (c.acceptSeq("CHARACTER", "SET") || c.accept("CHARSET")) {
                c.next();
            } else if (c.accept("AS")) {
                if (c.atSym("(")) c.groupText(false);
            } else if (c.atSym("(")) {
                c.groupText(false);
            } else {
                c.next();
            }
        }
        if (col.primaryKey) col.notNull = true;
        Column existing = t.columnByName(col.name);
        if (existing != null) {
            warnings.add("Duplicate column '" + col.name + "' in " + t.name + " ignored");
            return;
        }
        t.columns.add(col);
    }

    private static void normalizeSerial(Column col) {
        String u = col.type.toUpperCase(Locale.ROOT);
        switch (u) {
            case "SERIAL", "SERIAL4" -> { col.type = "INTEGER"; col.autoIncrement = true; }
            case "BIGSERIAL", "SERIAL8" -> { col.type = "BIGINT"; col.autoIncrement = true; }
            case "SMALLSERIAL", "SERIAL2" -> { col.type = "SMALLINT"; col.autoIncrement = true; }
            default -> { }
        }
    }

    private void references(Cursor c, Table from, String name, List<String> fromCols) {
        int line = c.line();
        String to = c.qualifiedName();
        List<String> toCols = c.atSym("(") ? c.nameList() : List.of();
        RefAction del = RefAction.NO_ACTION, upd = RefAction.NO_ACTION;
        while (!c.eof()) {
            if (c.acceptSeq("ON", "DELETE")) del = action(c);
            else if (c.acceptSeq("ON", "UPDATE")) upd = action(c);
            else if (c.accept("MATCH")) c.next();
            else if (c.atAny("DEFERRABLE", "INITIALLY", "NOT", "FOR", "REPLICATION", "IMMEDIATE", "DEFERRED", "ENABLE",
                    "DISABLE", "VALIDATE", "NOVALIDATE")) c.next();
            else break;
        }
        pending.add(new PendingFk(name, from, fromCols, to, toCols, del, upd, line));
    }

    private static RefAction action(Cursor c) {
        if (c.accept("CASCADE")) return RefAction.CASCADE;
        if (c.accept("RESTRICT")) return RefAction.RESTRICT;
        if (c.acceptSeq("NO", "ACTION")) return RefAction.NO_ACTION;
        if (c.acceptSeq("SET", "NULL")) return RefAction.SET_NULL;
        if (c.acceptSeq("SET", "DEFAULT")) return RefAction.SET_DEFAULT;
        c.next();
        return RefAction.NO_ACTION;
    }

    private void addIndex(Table t, String name, boolean unique, List<String> colNames) {
        TableIndex idx = new TableIndex();
        idx.name = name == null ? "" : name;
        idx.unique = unique;
        for (String n : colNames) {
            Column col = t.columnByName(n);
            if (col != null) idx.columnIds.add(col.id);
        }
        if (idx.columnIds.isEmpty()) return;
        if (idx.columnIds.size() == 1 && unique) {
            t.column(idx.columnIds.get(0)).unique = true;
            if (idx.name.isEmpty()) return;
        }
        t.indexes.add(idx);
    }

    private void resolveForeignKeys() {
        Set<String> seen = new HashSet<>();
        for (PendingFk fk : pending) {
            Table to = byName.get(key(fk.toTable));
            if (to == null) {
                warnings.add("Line " + fk.line + ": " + fk.from.name + " references unknown table '" + fk.toTable + "'");
                continue;
            }
            List<String> toCols = fk.toCols;
            if (toCols.isEmpty()) {
                toCols = new ArrayList<>();
                for (Column pk : to.primaryKey()) toCols.add(pk.name);
            }
            if (toCols.size() != fk.fromCols.size()) {
                warnings.add("Line " + fk.line + ": column count mismatch in foreign key of " + fk.from.name);
                continue;
            }
            boolean composite = fk.fromCols.size() > 1;
            for (int i = 0; i < toCols.size(); i++) {
                Column fc = fk.from.columnByName(fk.fromCols.get(i));
                Column tc = to.columnByName(toCols.get(i));
                if (fc == null || tc == null) {
                    warnings.add("Line " + fk.line + ": foreign key column not found ("
                            + fk.from.name + "." + fk.fromCols.get(i) + " -> " + to.name + "." + toCols.get(i) + ")");
                    continue;
                }
                if (!seen.add(fc.id + ">" + tc.id)) continue;
                Relation r = new Relation(fk.from, fc, to, tc);
                r.name = fk.name == null ? "" : fk.name;
                r.onDelete = fk.onDelete;
                r.onUpdate = fk.onUpdate;
                boolean singlePk = fk.from.primaryKey().size() == 1 && fc.primaryKey;
                r.type = !composite && (fc.unique || singlePk) ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
                diagram.relations.add(r);
            }
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ cursor

    private static final class Cursor {
        private static final Token EOF = new Token(Kind.SYMBOL, "<eof>", -1);
        final List<Token> toks;
        int pos;

        Cursor(List<Token> toks) {
            this.toks = toks;
        }

        boolean eof() { return pos >= toks.size(); }

        Token peek() { return peek(0); }

        Token peek(int k) { return pos + k < toks.size() ? toks.get(pos + k) : EOF; }

        int line() { return eof() ? (toks.isEmpty() ? 0 : toks.get(toks.size() - 1).line()) : peek().line(); }

        Token next() {
            if (eof()) throw new IllegalStateException("unexpected end of statement");
            return toks.get(pos++);
        }

        boolean at(String kw) { return peek().is(kw); }

        boolean atAny(String... kws) {
            for (String k : kws) if (peek().is(k)) return true;
            return false;
        }

        boolean atSym(String s) { return peek().sym(s); }

        boolean accept(String kw) {
            if (at(kw)) { pos++; return true; }
            return false;
        }

        boolean acceptSym(String s) {
            if (atSym(s)) { pos++; return true; }
            return false;
        }

        boolean acceptSeq(String... kws) {
            for (int i = 0; i < kws.length; i++) if (!peek(i).is(kws[i])) return false;
            pos += kws.length;
            return true;
        }

        List<String> nameParts() {
            List<String> parts = new ArrayList<>();
            parts.add(next().text());
            while (atSym(".")) {
                pos++;
                parts.add(next().text());
            }
            return parts;
        }

        /** schema.table -> table */
        String qualifiedName() {
            List<String> p = nameParts();
            return p.get(p.size() - 1);
        }

        /** ( a, b(10) DESC, `c` ) -> [a, b, c] */
        List<String> nameList() {
            List<String> names = new ArrayList<>();
            for (Cursor item : groupItems()) {
                if (!item.eof() && item.peek().isIdentifier()) names.add(item.next().text());
            }
            return names;
        }

        /** Consumes a parenthesised group and returns its comma-separated items as sub-cursors. */
        List<Cursor> groupItems() {
            List<Cursor> items = new ArrayList<>();
            if (!acceptSym("(")) return items;
            int depth = 1;
            List<Token> cur = new ArrayList<>();
            while (!eof()) {
                Token t = next();
                if (t.sym("(")) depth++;
                else if (t.sym(")")) {
                    depth--;
                    if (depth == 0) break;
                } else if (t.sym(",") && depth == 1) {
                    items.add(new Cursor(cur));
                    cur = new ArrayList<>();
                    continue;
                }
                cur.add(t);
            }
            items.add(new Cursor(cur));
            return items;
        }

        /** Remaining tokens split on top-level commas (ALTER TABLE x ADD ..., ADD ...). */
        List<Cursor> restItems() {
            List<Cursor> items = new ArrayList<>();
            List<Token> cur = new ArrayList<>();
            int depth = 0;
            while (!eof()) {
                Token t = next();
                if (t.sym("(")) depth++;
                if (t.sym(")")) depth--;
                if (t.sym(",") && depth == 0) {
                    items.add(new Cursor(cur));
                    cur = new ArrayList<>();
                    continue;
                }
                cur.add(t);
            }
            items.add(new Cursor(cur));
            return items;
        }

        /** Consumes a parenthesised group and returns it as compact SQL text. */
        String groupText(boolean upperWords) {
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            do {
                Token t = next();
                if (t.sym("(")) depth++;
                if (t.sym(")")) depth--;
                appendToken(sb, t, upperWords);
            } while (depth > 0 && !eof());
            return sb.toString();
        }

        /** A DEFAULT value: literal, function call, parenthesised expression, with optional ::casts. */
        String expression() {
            StringBuilder sb = new StringBuilder();
            if (atSym("(")) {
                sb.append(groupText(false));
            } else if (atSym("-") || atSym("+")) {
                sb.append(next().text()).append(next().text());
            } else {
                Token t = next();
                appendToken(sb, t, false);
                if (t.kind() == Kind.WORD && atSym("(")) sb.append(groupText(false));
            }
            while (atSym("::")) {
                sb.append(next().text());
                sb.append(next().text());
                while (peek().kind() == Kind.WORD && !COLUMN_STOP.contains(peek().text().toUpperCase(Locale.ROOT))) {
                    sb.append(' ').append(next().text());
                }
                if (atSym("(")) sb.append(groupText(false));
            }
            return sb.toString();
        }

        private static void appendToken(StringBuilder sb, Token t, boolean upperWords) {
            String text = switch (t.kind()) {
                case STRING -> "'" + t.text().replace("'", "''") + "'";
                case QUOTED -> t.text().isEmpty() ? "[]" : t.text();
                case WORD -> upperWords ? t.text().toUpperCase(Locale.ROOT) : t.text();
                default -> t.text();
            };
            boolean noSpaceBefore = t.sym(")") || t.sym(",") || t.sym("(") || t.sym(".") || t.sym("::");
            if (sb.length() > 0 && !noSpaceBefore) {
                char last = sb.charAt(sb.length() - 1);
                if (last != '(' && last != '.' && last != ':') sb.append(' ');
            }
            sb.append(text);
        }
    }
}
