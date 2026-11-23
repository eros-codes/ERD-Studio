package com.erdstudio.io;

import com.erdstudio.model.Area;
import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.Note;
import com.erdstudio.model.Palette;
import com.erdstudio.model.RefAction;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DBML (dbdiagram.io) <-> diagram. TableGroups map to areas, sticky notes to notes. */
public final class DbmlIO {

    private DbmlIO() {}

    // ================================================================== lexer

    private enum K { WORD, STR, QID, EXPR, NUM, COLOR, SYM, NL }

    private record Tok(K k, String t, int line) {
        boolean is(String w) { return (k == K.WORD) && t.equalsIgnoreCase(w); }
        boolean sym(String s) { return k == K.SYM && t.equals(s); }
        boolean name() { return k == K.WORD || k == K.QID; }
    }

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length(), line = 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') { out.add(new Tok(K.NL, "\n", line++)); i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') { while (i < n && s.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                int stop = e < 0 ? n : e + 2;
                for (int k = i; k < stop; k++) if (s.charAt(k) == '\n') line++;
                i = stop;
                continue;
            }
            if (s.startsWith("'''", i)) {
                int e = s.indexOf("'''", i + 3);
                int stop = e < 0 ? n : e;
                String v = s.substring(i + 3, stop);
                for (char ch : v.toCharArray()) if (ch == '\n') line++;
                out.add(new Tok(K.STR, dedent(v), line));
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\' && i + 1 < n) { sb.append(s.charAt(i + 1)); i += 2; continue; }
                    if (s.charAt(i) == '\n') line++;
                    sb.append(s.charAt(i++));
                }
                i++;
                out.add(new Tok(c == '\'' ? K.STR : c == '"' ? K.QID : K.EXPR, sb.toString(), line));
                continue;
            }
            if (c == '#') {
                int st = i++;
                while (i < n && Character.isLetterOrDigit(s.charAt(i))) i++;
                out.add(new Tok(K.COLOR, s.substring(st, i), line));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)) && prevAllowsNumber(out))) {
                int st = i++;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                out.add(new Tok(K.NUM, s.substring(st, i), line));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int st = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                out.add(new Tok(K.WORD, s.substring(st, i), line));
                continue;
            }
            if (c == '<' && i + 1 < n && s.charAt(i + 1) == '>') { out.add(new Tok(K.SYM, "<>", line)); i += 2; continue; }
            out.add(new Tok(K.SYM, String.valueOf(c), line));
            i++;
        }
        return out;
    }

    private static boolean prevAllowsNumber(List<Tok> out) {
        if (out.isEmpty()) return true;
        Tok p = out.get(out.size() - 1);
        return p.sym(":") || p.sym(",") || p.sym("(") || p.sym("[");
    }

    private static String dedent(String v) {
        String[] lines = v.replace("\r", "").split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (String l : lines) {
            if (l.isBlank()) continue;
            int k = 0;
            while (k < l.length() && l.charAt(k) == ' ') k++;
            min = Math.min(min, k);
        }
        if (min == Integer.MAX_VALUE) min = 0;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l.length() >= min ? l.substring(min) : l.trim()).append('\n');
        return sb.toString().strip();
    }

    // ================================================================== parser

    private static final class P {
        final List<Tok> t;
        int p;

        P(List<Tok> t) { this.t = t; }

        boolean eof() { return p >= t.size(); }

        Tok peek() { return p < t.size() ? t.get(p) : new Tok(K.SYM, "<eof>", -1); }

        Tok peekNonNl(int k) {
            int q = p;
            while (q < t.size() && t.get(q).k == K.NL) q++;
            q += k;
            return q < t.size() ? t.get(q) : new Tok(K.SYM, "<eof>", -1);
        }

        Tok next() { return t.get(p++); }

        void skipNl() { while (!eof() && peek().k == K.NL) p++; }

        boolean acceptSym(String s) {
            skipNl();
            if (peek().sym(s)) { p++; return true; }
            return false;
        }

        /** name or schema.name -> name */
        String name() {
            skipNl();
            String n = next().t;
            while (peek().sym(".") && p + 1 < t.size() && t.get(p + 1).name()) { p++; n = next().t; }
            return n;
        }

        /** Skips a balanced { } block (cursor at '{'). */
        void skipBlock() {
            skipNl();
            if (!peek().sym("{")) return;
            int depth = 0;
            while (!eof()) {
                Tok k = next();
                if (k.sym("{")) depth++;
                if (k.sym("}") && --depth == 0) return;
            }
        }

        /** [a: b, c, d: 'x'] -> ordered key/value list (value null for flags). */
        List<String[]> settings() {
            List<String[]> out = new ArrayList<>();
            if (!peek().sym("[")) return out;
            p++;
            while (!eof() && !peek().sym("]")) {
                if (peek().sym(",") || peek().k == K.NL) { p++; continue; }
                StringBuilder key = new StringBuilder();
                while (!eof() && !peek().sym(":") && !peek().sym(",") && !peek().sym("]")) {
                    Tok k = next();
                    if (k.k == K.NL) continue;
                    if (key.length() > 0 && k.k == K.WORD) key.append(' ');
                    key.append(k.t);
                }
                String value = null;
                if (peek().sym(":")) {
                    p++;
                    StringBuilder v = new StringBuilder();
                    Tok first = null, prev = null;
                    while (!eof() && !peek().sym(",") && !peek().sym("]")) {
                        Tok k = next();
                        if (k.k == K.NL) continue;
                        if (first == null) first = k;
                        if (prev != null && prev.k == K.WORD && k.k == K.WORD) v.append(' ');
                        v.append(render(k));
                        prev = k;
                    }
                    value = first != null && first.k == K.STR && v.toString().equals(render(first)) ? "\u0001" + first.t
                            : first != null && first.k == K.EXPR && v.toString().equals(render(first)) ? "\u0002" + first.t : v.toString();
                }
                out.add(new String[]{key.toString().toLowerCase(Locale.ROOT).trim(), value});
            }
            if (peek().sym("]")) p++;
            return out;
        }

        private static String render(Tok k) {
            return switch (k.k) {
                case STR -> "'" + k.t + "'";
                case QID -> "\"" + k.t + "\"";
                case EXPR -> "`" + k.t + "`";
                default -> k.t;
            };
        }
    }

    private record PendingRef(String name, String fromTable, List<String> fromCols, String op, String toTable,
                              List<String> toCols, List<String[]> settings, int line) {}

    public static ImportResult parse(String text) {
        List<String> warnings = new ArrayList<>();
        Diagram d = new Diagram();
        Map<String, Table> tables = new LinkedHashMap<>();
        Map<String, String> alias = new HashMap<>();
        Map<String, List<String>> enums = new HashMap<>();
        List<PendingRef> refs = new ArrayList<>();
        Map<String, Set<String>> areaMembers = new LinkedHashMap<>();
        Map<Area, List<String>> groupTables = new LinkedHashMap<>();
        boolean colors = false;

        P p = new P(lex(text));
        while (true) {
            p.skipNl();
            if (p.eof()) break;
            Tok k = p.next();
            try {
                if (k.is("Project")) {
                    if (p.peek().name()) d.name = p.next().t;
                    p.skipNl();
                    if (p.peek().sym("{")) {
                        int start = p.p;
                        p.skipBlock();
                        for (int i = start; i < p.p - 1; i++) {
                            Tok a = p.t.get(i);
                            if (a.is("database_type") && i + 2 < p.p && p.t.get(i + 2).k == K.STR) {
                                String db = p.t.get(i + 2).t.toLowerCase(Locale.ROOT);
                                d.dialect = db.contains("mysql") || db.contains("maria") ? Dialect.MYSQL
                                        : db.contains("sqlite") ? Dialect.SQLITE
                                        : db.contains("sql server") || db.contains("mssql") ? Dialect.SQLSERVER : Dialect.POSTGRESQL;
                            }
                        }
                    }
                } else if (k.is("Table")) {
                    Table t = parseTable(p, refs, alias, warnings);
                    if (t != null) {
                        if (!t.color.equals(Palette.DEFAULT_TABLE)) colors = true;
                        tables.put(t.name.toLowerCase(Locale.ROOT), t);
                        d.tables.add(t);
                    }
                } else if (k.is("Ref")) {
                    String name = "";
                    if (p.peek().name() && !p.peekNonNl(1).sym(".")) name = p.next().t;
                    p.skipNl();
                    if (p.peek().sym(":")) {
                        p.next();
                        refs.add(parseRef(p, name));
                    } else if (p.peek().sym("{")) {
                        p.next();
                        while (true) {
                            p.skipNl();
                            if (p.eof() || p.peek().sym("}")) { if (!p.eof()) p.next(); break; }
                            refs.add(parseRef(p, name));
                        }
                    }
                } else if (k.is("Enum")) {
                    String name = p.name();
                    List<String> values = new ArrayList<>();
                    p.skipNl();
                    if (p.acceptSym("{")) {
                        while (true) {
                            p.skipNl();
                            if (p.eof() || p.peek().sym("}")) { if (!p.eof()) p.next(); break; }
                            Tok v = p.next();
                            values.add(v.t);
                            p.settings();
                        }
                    }
                    enums.put(name.toLowerCase(Locale.ROOT), values);
                } else if (k.is("TableGroup")) {
                    Area a = new Area();
                    a.name = p.name();
                    a.color = Palette.AREA_COLORS[groupTables.size() % Palette.AREA_COLORS.length];
                    for (String[] s : p.settings()) if (s[0].equals("color") && s[1] != null) a.color = normColor(s[1]);
                    List<String> members = new ArrayList<>();
                    p.skipNl();
                    if (p.acceptSym("{")) {
                        while (true) {
                            p.skipNl();
                            if (p.eof() || p.peek().sym("}")) { if (!p.eof()) p.next(); break; }
                            if (p.peek().is("Note")) { p.next(); if (p.acceptSym(":")) p.next(); else p.skipBlock(); continue; }
                            members.add(p.name());
                        }
                    }
                    groupTables.put(a, members);
                } else if (k.is("Note")) {
                    Note n = new Note();
                    if (p.peek().name()) p.next();
                    p.skipNl();
                    if (p.acceptSym("{")) {
                        StringBuilder sb = new StringBuilder();
                        while (true) {
                            p.skipNl();
                            if (p.eof() || p.peek().sym("}")) { if (!p.eof()) p.next(); break; }
                            Tok v = p.next();
                            if (v.k == K.STR) sb.append(sb.length() > 0 ? "\n" : "").append(v.t);
                            else if (v.sym("[")) { p.p--; p.settings(); }
                        }
                        n.text = sb.toString();
                    } else if (p.acceptSym(":")) {
                        n.text = p.next().t;
                    }
                    d.notes.add(n);
                } else if (k.is("TablePartial") || k.is("Records")) {
                    p.name();
                    p.settings();
                    p.skipBlock();
                } else {
                    // unknown top-level thing: skip to end of line or block
                    while (!p.eof() && p.peek().k != K.NL && !p.peek().sym("{")) p.next();
                    if (p.peek().sym("{")) p.skipBlock();
                }
            } catch (RuntimeException e) {
                warnings.add("Line " + k.line() + ": could not parse (" + e.getMessage() + ")");
                while (!p.eof() && p.peek().k != K.NL) p.next();
            }
        }

        // enum-typed columns -> ENUM('a','b')
        for (Table t : d.tables) {
            for (Column c : t.columns) {
                List<String> vals = enums.get(c.type.toLowerCase(Locale.ROOT).replaceAll("^.*\\.", ""));
                if (vals == null) continue;
                StringBuilder sb = new StringBuilder("ENUM(");
                for (int i = 0; i < vals.size(); i++) sb.append(i > 0 ? ", " : "").append('\'').append(vals.get(i).replace("'", "''")).append('\'');
                c.type = sb.append(')').toString();
            }
        }

        for (PendingRef r : refs) resolveRef(d, tables, alias, r, warnings);

        for (Map.Entry<Area, List<String>> e : groupTables.entrySet()) {
            Set<String> ids = new LinkedHashSet<>();
            for (String n : e.getValue()) {
                Table t = lookup(tables, alias, n);
                if (t != null) ids.add(t.id);
                else warnings.add("TableGroup " + e.getKey().name + ": unknown table " + n);
            }
            d.areas.add(e.getKey());
            areaMembers.put(e.getKey().id, ids);
        }
        // notes without positions: park them on the left
        double ny = 80;
        for (Note n : d.notes) { n.x = -200; n.y = ny; ny += n.height + 30; }
        return new ImportResult(d, warnings, colors, areaMembers);
    }

    private static Table parseTable(P p, List<PendingRef> refs, Map<String, String> alias, List<String> warnings) {
        String name = p.name();
        if (p.peek().is("as")) { p.next(); alias.put(p.next().t.toLowerCase(Locale.ROOT), name); }
        Table t = new Table(name);
        for (String[] s : p.settings()) {
            if (s[0].equals("headercolor") && s[1] != null) t.color = normColor(s[1]);
            if (s[0].equals("note") && s[1] != null) t.comment = str(s[1]);
        }
        p.skipNl();
        if (!p.acceptSym("{")) return null;
        while (true) {
            p.skipNl();
            if (p.eof()) break;
            if (p.peek().sym("}")) { p.next(); break; }
            Tok first = p.peek();
            if (first.is("Indexes") || first.is("indexes")) {
                p.next();
                p.skipNl();
                p.acceptSym("{");
                while (true) {
                    p.skipNl();
                    if (p.eof() || p.peek().sym("}")) { if (!p.eof()) p.next(); break; }
                    parseIndex(p, t);
                }
                continue;
            }
            if (first.is("Note") && (p.peekNonNl(1).sym(":") || p.peekNonNl(1).sym("{"))) {
                p.next();
                p.skipNl();
                if (p.acceptSym(":")) { p.skipNl(); t.comment = p.next().t; }
                else if (p.acceptSym("{")) {
                    while (true) {
                        p.skipNl();
                        if (p.eof() || p.peek().sym("}")) { if (!p.eof()) p.next(); break; }
                        Tok v = p.next();
                        if (v.k == K.STR) t.comment = v.t;
                    }
                }
                continue;
            }
            if (first.sym("~")) { while (!p.eof() && p.peek().k != K.NL) p.next(); continue; } // partial injection
            parseColumn(p, t, refs, warnings);
        }
        return t;
    }

    private static void parseColumn(P p, Table t, List<PendingRef> refs, List<String> warnings) {
        Tok nameTok = p.next();
        Column c = new Column(nameTok.t, "");
        StringBuilder type = new StringBuilder();
        while (!p.eof() && p.peek().k != K.NL && !p.peek().sym("[") && !p.peek().sym("}")) {
            Tok k = p.next();
            if (k.sym("(")) {
                StringBuilder args = new StringBuilder("(");
                while (!p.eof() && !p.peek().sym(")")) {
                    Tok a = p.next();
                    if (a.sym(",")) args.append(", ");
                    else if (a.k == K.STR) args.append('\'').append(a.t).append('\'');
                    else args.append(a.t);
                }
                p.next();
                type.append(args).append(')');
            } else if (k.sym("[") || k.sym("]")) {
                type.append(k.t);
            } else if (k.sym(".")) {
                type.setLength(0); // schema.enum -> enum
            } else {
                if (type.length() > 0 && !type.toString().endsWith("(")) type.append(' ');
                type.append(k.t);
            }
        }
        // int[] is lexed as int + [ ] : the '[' could also start settings, so check for an immediate ']'
        if (p.peek().sym("[") && p.p + 1 < p.t.size() && p.t.get(p.p + 1).sym("]")) {
            p.p += 2;
            type.append("[]");
        }
        String ty = type.toString().trim();
        c.type = ty.isEmpty() ? "VARCHAR" : (ty.matches("[a-z0-9 ()_,'\\[\\]]+") ? ty.toUpperCase(Locale.ROOT) : ty);
        normalize(c);
        for (String[] s : p.settings()) {
            String key = s[0];
            switch (key) {
                case "pk", "primary key" -> { c.primaryKey = true; c.notNull = true; }
                case "increment" -> c.autoIncrement = true;
                case "not null" -> c.notNull = true;
                case "null" -> c.notNull = false;
                case "unique" -> c.unique = true;
                case "note" -> c.comment = str(s[1]);
                case "default" -> c.defaultValue = defaultOf(s[1]);
                case "ref" -> {
                    if (s[1] == null) break;
                    String v = s[1].trim();
                    String op = v.startsWith("<>") ? "<>" : v.substring(0, 1);
                    String target = v.substring(op.length()).trim();
                    int dot = target.lastIndexOf('.');
                    if (dot < 0) { warnings.add("Line " + nameTok.line() + ": bad inline ref " + v); break; }
                    String tt = unquote(target.substring(0, dot));
                    if (tt.contains(".")) tt = tt.substring(tt.lastIndexOf('.') + 1);
                    refs.add(new PendingRef("", t.name, List.of(c.name), op, unquote(tt), List.of(unquote(target.substring(dot + 1))),
                            List.of(), nameTok.line()));
                }
                default -> { }
            }
        }
        if (c.primaryKey) c.notNull = true;
        t.columns.add(c);
    }

    private static void normalize(Column c) {
        switch (c.type.toUpperCase(Locale.ROOT)) {
            case "SERIAL" -> { c.type = "INTEGER"; c.autoIncrement = true; }
            case "BIGSERIAL" -> { c.type = "BIGINT"; c.autoIncrement = true; }
            case "SMALLSERIAL" -> { c.type = "SMALLINT"; c.autoIncrement = true; }
            case "INT" -> c.type = "INTEGER";
            default -> { }
        }
    }

    private static void parseIndex(P p, Table t) {
        List<String> cols = new ArrayList<>();
        Tok k = p.next();
        if (k.sym("(")) {
            while (!p.eof() && !p.peek().sym(")")) {
                Tok a = p.next();
                if (a.name()) cols.add(a.t);
            }
            p.next();
        } else if (k.name()) {
            cols.add(k.t);
        }
        TableIndex idx = new TableIndex();
        boolean pk = false;
        for (String[] s : p.settings()) {
            if (s[0].equals("unique")) idx.unique = true;
            if (s[0].equals("pk")) pk = true;
            if (s[0].equals("name") && s[1] != null) idx.name = str(s[1]);
        }
        for (String n : cols) {
            Column c = t.columnByName(n);
            if (c != null) idx.columnIds.add(c.id);
        }
        if (idx.columnIds.isEmpty()) return;
        if (pk) {
            for (String id : idx.columnIds) { Column c = t.column(id); c.primaryKey = true; c.notNull = true; }
            return;
        }
        if (idx.unique && idx.columnIds.size() == 1 && idx.name.isEmpty()) { t.column(idx.columnIds.get(0)).unique = true; return; }
        t.indexes.add(idx);
    }

    private static PendingRef parseRef(P p, String name) {
        p.skipNl();
        int line = p.peek().line();
        String[] a = endpoint(p);
        p.skipNl();
        String op = p.next().t;
        if (op.equals("<") && p.peek().sym(">")) { p.next(); op = "<>"; }
        String[] b = endpoint(p);
        List<String[]> settings = p.settings();
        return new PendingRef(name, a[0], List.of(a[1].split(",")), op, b[0], List.of(b[1].split(",")), settings, line);
    }

    /** table.col | schema.table.col | table.(a, b) -> {table, "a,b"} */
    private static String[] endpoint(P p) {
        List<String> parts = new ArrayList<>();
        String cols = null;
        p.skipNl();
        parts.add(p.next().t);
        while (p.peek().sym(".")) {
            p.next();
            if (p.peek().sym("(")) {
                p.next();
                List<String> cs = new ArrayList<>();
                while (!p.eof() && !p.peek().sym(")")) { Tok k = p.next(); if (k.name()) cs.add(k.t); }
                p.next();
                cols = String.join(",", cs);
            } else {
                parts.add(p.next().t);
            }
        }
        if (cols == null) {
            cols = parts.remove(parts.size() - 1);
        }
        return new String[]{parts.get(parts.size() - 1), cols};
    }

    private static Table lookup(Map<String, Table> tables, Map<String, String> alias, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Table t = tables.get(n);
        if (t == null && alias.containsKey(n)) t = tables.get(alias.get(n).toLowerCase(Locale.ROOT));
        return t;
    }

    private static void resolveRef(Diagram d, Map<String, Table> tables, Map<String, String> alias, PendingRef r, List<String> warnings) {
        if (r.op.equals("<>")) {
            warnings.add("Line " + r.line + ": many-to-many ref " + r.fromTable + " <> " + r.toTable + " skipped (add a junction table)");
            return;
        }
        Table a = lookup(tables, alias, r.fromTable), b = lookup(tables, alias, r.toTable);
        if (a == null || b == null) {
            warnings.add("Line " + r.line + ": ref to unknown table " + (a == null ? r.fromTable : r.toTable));
            return;
        }
        // '>' : a is many (FK side). '<' : b is many. '-' : one-to-one, a holds the FK.
        Table child = r.op.equals("<") ? b : a, parent = r.op.equals("<") ? a : b;
        List<String> cc = r.op.equals("<") ? r.toCols : r.fromCols, pc = r.op.equals("<") ? r.fromCols : r.toCols;
        if (cc.size() != pc.size()) { warnings.add("Line " + r.line + ": column count mismatch in ref"); return; }
        for (int i = 0; i < cc.size(); i++) {
            Column fc = child.columnByName(cc.get(i).trim()), tc = parent.columnByName(pc.get(i).trim());
            if (fc == null || tc == null) {
                warnings.add("Line " + r.line + ": ref column not found (" + child.name + "." + cc.get(i) + " / " + parent.name + "." + pc.get(i) + ")");
                continue;
            }
            Relation rel = new Relation(child, fc, parent, tc);
            rel.type = r.op.equals("-") ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
            rel.name = r.name == null ? "" : r.name;
            if (cc.size() > 1 && rel.name.isEmpty()) rel.name = "fk_" + child.name + "_" + parent.name;
            for (String[] s : r.settings) {
                if (s[1] == null) continue;
                if (s[0].equals("delete")) rel.onDelete = RefAction.parse(s[1]);
                if (s[0].equals("update")) rel.onUpdate = RefAction.parse(s[1]);
            }
            d.relations.add(rel);
        }
    }

    private static String str(String v) {
        if (v == null) return "";
        if (v.startsWith("\u0001")) return v.substring(1);
        return unquote(v);
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String defaultOf(String v) {
        if (v == null) return "";
        if (v.startsWith("\u0001")) return "'" + v.substring(1).replace("'", "''") + "'";
        if (v.startsWith("\u0002")) return v.substring(1);
        return v.trim();
    }

    private static String normColor(String v) {
        String s = v.trim();
        if (s.matches("#[0-9A-Fa-f]{3}")) s = "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3);
        return s.matches("#[0-9A-Fa-f]{6}") ? s.toLowerCase(Locale.ROOT) : Palette.DEFAULT_TABLE;
    }

    // ================================================================== export

    /**
     * @param areaMembers area id -> table ids inside it (computed by the caller from geometry)
     */
    public static String export(Diagram d, Map<String, List<String>> areaMembers) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(d.name).append(" — generated by ERD Studio\n\n");
        sb.append("Project ").append(name(d.name)).append(" {\n  database_type: '").append(switch (d.dialect) {
            case POSTGRESQL -> "PostgreSQL";
            case MYSQL -> "MySQL";
            case SQLITE -> "SQLite";
            case SQLSERVER -> "SQL Server";
        }).append("'\n}\n");

        Map<String, List<Relation>> fkGroups = SqlExporter.groupForeignKeys(d);
        for (Table t : d.tables) {
            sb.append("\nTable ").append(name(t.name));
            List<String> ts = new ArrayList<>();
            ts.add("headercolor: " + t.color.toUpperCase(Locale.ROOT));
            sb.append(" [").append(String.join(", ", ts)).append("] {\n");
            int w = 1, tw = 1;
            for (Column c : t.columns) { w = Math.max(w, name(c.name).length()); tw = Math.max(tw, type(c).length()); }
            List<Column> pk = t.primaryKey();
            for (Column c : t.columns) {
                sb.append("  ").append(pad(name(c.name), w)).append(' ').append(pad(type(c), tw));
                List<String> s = new ArrayList<>();
                if (c.primaryKey && pk.size() == 1) s.add("pk");
                if (c.autoIncrement) s.add("increment");
                if (c.notNull && !c.primaryKey) s.add("not null");
                if (c.unique && !c.primaryKey) s.add("unique");
                if (c.defaultValue != null && !c.defaultValue.isBlank()) s.add("default: " + dbmlDefault(c.defaultValue.trim()));
                if (!c.comment.isBlank()) s.add("note: " + quote(c.comment));
                if (!s.isEmpty()) sb.append(" [").append(String.join(", ", s)).append(']');
                sb.append('\n');
            }
            boolean hasIdx = pk.size() > 1 || !t.indexes.isEmpty();
            if (hasIdx) {
                sb.append("\n  Indexes {\n");
                if (pk.size() > 1) {
                    List<String> n = new ArrayList<>();
                    for (Column c : pk) n.add(name(c.name));
                    sb.append("    (").append(String.join(", ", n)).append(") [pk]\n");
                }
                for (TableIndex idx : t.indexes) {
                    List<String> n = new ArrayList<>();
                    for (String cid : idx.columnIds) { Column c = t.column(cid); if (c != null) n.add(name(c.name)); }
                    if (n.isEmpty()) continue;
                    sb.append("    ").append(n.size() == 1 ? n.get(0) : "(" + String.join(", ", n) + ")");
                    List<String> s = new ArrayList<>();
                    if (idx.unique) s.add("unique");
                    if (idx.name != null && !idx.name.isBlank()) s.add("name: " + quote(idx.name));
                    if (!s.isEmpty()) sb.append(" [").append(String.join(", ", s)).append(']');
                    sb.append('\n');
                }
                sb.append("  }\n");
            }
            if (!t.comment.isBlank()) sb.append("\n  Note: ").append(t.comment.contains("\n") ? "'''\n    " + t.comment.replace("\n", "\n    ") + "\n  '''" : quote(t.comment)).append('\n');
            sb.append("}\n");
        }

        if (!fkGroups.isEmpty()) sb.append('\n');
        for (List<Relation> g : fkGroups.values()) {
            Relation r = g.get(0);
            Table f = d.table(r.fromTableId), t = d.table(r.toTableId);
            List<String> fc = new ArrayList<>(), tc = new ArrayList<>();
            for (Relation x : g) { fc.add(name(f.column(x.fromColumnId).name)); tc.add(name(t.column(x.toColumnId).name)); }
            sb.append("Ref");
            if (r.name != null && !r.name.isBlank()) sb.append(' ').append(name(r.name));
            sb.append(": ").append(name(f.name)).append('.').append(fc.size() == 1 ? fc.get(0) : "(" + String.join(", ", fc) + ")")
                    .append(r.type == RelationType.ONE_TO_ONE ? " - " : " > ")
                    .append(name(t.name)).append('.').append(tc.size() == 1 ? tc.get(0) : "(" + String.join(", ", tc) + ")");
            List<String> s = new ArrayList<>();
            if (r.onDelete != null && r.onDelete != RefAction.NO_ACTION) s.add("delete: " + r.onDelete.sql.toLowerCase(Locale.ROOT));
            if (r.onUpdate != null && r.onUpdate != RefAction.NO_ACTION) s.add("update: " + r.onUpdate.sql.toLowerCase(Locale.ROOT));
            if (!s.isEmpty()) sb.append(" [").append(String.join(", ", s)).append(']');
            sb.append('\n');
        }

        for (Area a : d.areas) {
            List<String> members = areaMembers.getOrDefault(a.id, List.of());
            sb.append("\nTableGroup ").append(name(a.name)).append(" [color: ").append(a.color.toUpperCase(Locale.ROOT)).append("] {\n");
            for (String id : members) { Table t = d.table(id); if (t != null) sb.append("  ").append(name(t.name)).append('\n'); }
            sb.append("}\n");
        }
        int i = 1;
        for (Note n : d.notes) {
            sb.append("\nNote note_").append(i++).append(" {\n  '''\n  ").append(n.text.replace("'''", "\\'\\'\\'").replace("\n", "\n  ")).append("\n  '''\n}\n");
        }
        return sb.toString();
    }

    private static String type(Column c) {
        String t = c.type == null || c.type.isBlank() ? "varchar" : c.type.toLowerCase(Locale.ROOT);
        if (t.startsWith("enum(")) t = "varchar"; // DBML enums are separate declarations; keep it simple
        return t.matches("[a-z0-9_]+(\\([0-9, ]+\\))?(\\[\\])?") ? t : "\"" + t.replace("\"", "'") + "\"";
    }

    private static String dbmlDefault(String v) {
        if (v.matches("-?\\d+(\\.\\d+)?")) return v;
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("null")) return v.toLowerCase(Locale.ROOT);
        if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2 && !v.contains("::")) return v;
        return "`" + v.replace("`", "'") + "`";
    }

    private static String name(String n) {
        return n.matches("[A-Za-z_][A-Za-z0-9_]*") ? n : "\"" + n.replace("\"", "'") + "\"";
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
