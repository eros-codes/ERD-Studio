package com.erdstudio.io;

import java.util.ArrayList;
import java.util.List;

/**
 * A forgiving SQL tokenizer that understands the quoting and comment styles of
 * PostgreSQL, MySQL/MariaDB, SQLite and SQL Server.
 */
final class SqlLexer {

    enum Kind { WORD, QUOTED, STRING, NUMBER, SYMBOL }

    record Token(Kind kind, String text, int line) {
        boolean is(String keyword) {
            return kind == Kind.WORD && text.equalsIgnoreCase(keyword);
        }

        boolean sym(String s) {
            return kind == Kind.SYMBOL && text.equals(s);
        }

        boolean isIdentifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private SqlLexer() {}

    static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = sql.length(), line = 1;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }

            // -- comment, # comment (MySQL)
            if ((c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') || c == '#') {
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            // /* block comment */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) if (sql.charAt(k) == '\n') line++;
                i = stop;
                continue;
            }
            // 'string' (also N'..', E'..' prefixes handled by WORD + STRING merge below)
            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') { sb.append('\''); i += 2; continue; }
                    if (d == '\\' && i + 1 < n && sql.charAt(i + 1) == '\'') { sb.append('\''); i += 2; continue; }
                    if (d == '\'') { i++; break; }
                    if (d == '\n') line++;
                    sb.append(d);
                    i++;
                }
                Token prev = out.isEmpty() ? null : out.get(out.size() - 1);
                if (prev != null && prev.kind == Kind.WORD && prev.text.length() == 1
                        && "NnEeBbXx".indexOf(prev.text.charAt(0)) >= 0) {
                    out.remove(out.size() - 1);
                }
                out.add(new Token(Kind.STRING, sb.toString(), line));
                continue;
            }
            // "ident", `ident`, [ident]
            if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == close && i + 1 < n && sql.charAt(i + 1) == close) { sb.append(d); i += 2; continue; }
                    if (d == close) { i++; break; }
                    sb.append(d);
                    i++;
                }
                out.add(new Token(Kind.QUOTED, sb.toString(), line));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.add(new Token(Kind.NUMBER, sql.substring(s, i), line));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '@' || c == '$') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$'
                        || sql.charAt(i) == '@')) i++;
                out.add(new Token(Kind.WORD, sql.substring(s, i), line));
                continue;
            }
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                out.add(new Token(Kind.SYMBOL, "::", line));
                i += 2;
                continue;
            }
            out.add(new Token(Kind.SYMBOL, String.valueOf(c), line));
            i++;
        }
        return out;
    }

    /** Splits a token stream into statements on top-level ';' and SQL Server's GO. */
    static List<List<Token>> statements(List<Token> tokens) {
        List<List<Token>> out = new ArrayList<>();
        List<Token> cur = new ArrayList<>();
        int depth = 0;
        int lastLine = -1;
        for (Token t : tokens) {
            if (t.sym("(")) depth++;
            if (t.sym(")")) depth = Math.max(0, depth - 1);
            boolean go = t.is("GO") && depth == 0 && t.line() != lastLine;
            if ((t.sym(";") && depth == 0) || go) {
                if (!cur.isEmpty()) out.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
            lastLine = t.line();
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }
}
