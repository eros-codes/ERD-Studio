package com.erdstudio.io;

import com.erdstudio.model.Area;
import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Note;
import com.erdstudio.model.RefAction;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Mermaid erDiagram and a Markdown data dictionary. */
public final class DocsExporter {

    private DocsExporter() {}

    // ------------------------------------------------------------------ mermaid

    public static String mermaid(Diagram d) {
        StringBuilder sb = new StringBuilder("erDiagram\n");
        Set<String> fk = new HashSet<>();
        for (Relation r : d.relations) fk.add(r.fromColumnId);
        for (List<Relation> g : SqlExporter.groupForeignKeys(d).values()) {
            Relation r = g.get(0);
            Table child = d.table(r.fromTableId), parent = d.table(r.toTableId);
            boolean required = true;
            List<String> cols = new ArrayList<>();
            for (Relation x : g) {
                Column c = child.column(x.fromColumnId);
                if (!c.isRequired()) required = false;
                cols.add(c.name);
            }
            String left = required ? "||" : "|o";
            String right = r.type == RelationType.ONE_TO_ONE ? "o|" : "o{";
            sb.append("    ").append(entity(parent.name)).append(' ').append(left).append("--").append(right).append(' ')
                    .append(entity(child.name)).append(" : \"").append(String.join(", ", cols).replace("\"", "'")).append("\"\n");
        }
        for (Table t : d.tables) {
            sb.append("    ").append(entity(t.name)).append(" {\n");
            for (Column c : t.columns) {
                sb.append("        ").append(mType(c.type)).append(' ').append(attr(c.name));
                List<String> keys = new ArrayList<>();
                if (c.primaryKey) keys.add("PK");
                if (fk.contains(c.id)) keys.add("FK");
                if (c.unique && !c.primaryKey) keys.add("UK");
                if (!keys.isEmpty()) sb.append(' ').append(String.join(", ", keys));
                if (!c.comment.isBlank()) sb.append(" \"").append(c.comment.replace("\"", "'").replace("\n", " ")).append('"');
                sb.append('\n');
            }
            sb.append("    }\n");
        }
        return sb.toString();
    }

    private static String entity(String n) {
        String s = n.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) ? "t_" + s : s;
    }

    private static String attr(String n) {
        String s = n.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isEmpty() || Character.isDigit(s.charAt(0)) ? "c_" + s : s;
    }

    private static String mType(String type) {
        String t = (type == null || type.isBlank() ? "text" : type.toLowerCase(Locale.ROOT)).trim();
        t = t.replaceAll("\\s*,\\s*", "-").replaceAll("\\s+", "_").replaceAll("[^a-z0-9_\\-()\\[\\]]", "");
        if (t.isEmpty() || !Character.isLetter(t.charAt(0))) t = "t_" + t;
        if (t.length() > 40) t = t.substring(0, 40);
        return t;
    }

    // ------------------------------------------------------------------ markdown

    /**
     * @param areaMembers area id -> table ids inside it
     * @param includeMermaid embed the Mermaid diagram (renders on GitHub, GitLab, Obsidian, …)
     */
    public static String markdown(Diagram d, Map<String, List<String>> areaMembers, boolean includeMermaid) {
        StringBuilder sb = new StringBuilder();
        int cols = 0;
        for (Table t : d.tables) cols += t.columns.size();
        sb.append("# ").append(d.name).append("\n\n");
        sb.append("> ").append(d.dialect.label).append(" · ").append(d.tables.size()).append(" tables · ").append(cols)
                .append(" columns · ").append(d.relations.size()).append(" relations · generated ").append(LocalDate.now())
                .append(" by ERD Studio\n\n");

        List<Table> tables = new ArrayList<>(d.tables);
        tables.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        sb.append("## Contents\n\n");
        for (Table t : tables) {
            sb.append("- [").append(md(t.name)).append("](#").append(anchor(t.name)).append(")");
            if (!t.comment.isBlank()) sb.append(" — ").append(md(firstLine(t.comment)));
            sb.append('\n');
        }
        sb.append('\n');

        if (!d.areas.isEmpty()) {
            sb.append("## Areas\n\n");
            for (Area a : d.areas) {
                List<String> names = new ArrayList<>();
                for (String id : areaMembers.getOrDefault(a.id, List.of())) {
                    Table t = d.table(id);
                    if (t != null) names.add("[" + md(t.name) + "](#" + anchor(t.name) + ")");
                }
                sb.append("- **").append(md(a.name)).append("**: ").append(names.isEmpty() ? "_empty_" : String.join(", ", names)).append('\n');
            }
            sb.append('\n');
        }

        if (includeMermaid && !d.tables.isEmpty()) {
            sb.append("## Diagram\n\n```mermaid\n").append(mermaid(d)).append("```\n\n");
        }

        sb.append("## Tables\n");
        for (Table t : tables) {
            sb.append("\n### ").append(md(t.name)).append("\n\n");
            if (!t.comment.isBlank()) sb.append(md(t.comment)).append("\n\n");
            sb.append("| # | Column | Type | Nullable | Key | Default | Comment |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            Set<String> fk = new HashSet<>();
            for (Relation r : d.relations) if (r.fromTableId.equals(t.id)) fk.add(r.fromColumnId);
            int i = 1;
            for (Column c : t.columns) {
                List<String> keys = new ArrayList<>();
                if (c.primaryKey) keys.add("PK");
                if (fk.contains(c.id)) keys.add("FK");
                if (c.unique && !c.primaryKey) keys.add("UQ");
                if (c.autoIncrement) keys.add("AI");
                sb.append("| ").append(i++).append(" | `").append(c.name).append("` | `").append(c.type == null ? "" : c.type.toLowerCase(Locale.ROOT))
                        .append("` | ").append(c.isRequired() ? "no" : "yes").append(" | ").append(String.join(", ", keys)).append(" | ")
                        .append(c.defaultValue == null || c.defaultValue.isBlank() ? "" : "`" + c.defaultValue.replace("|", "\\|") + "`")
                        .append(" | ").append(md(c.comment).replace("\n", "<br>")).append(" |\n");
            }
            if (!t.indexes.isEmpty()) {
                sb.append("\n**Indexes**\n\n");
                for (TableIndex idx : t.indexes) {
                    List<String> n = new ArrayList<>();
                    for (String cid : idx.columnIds) { Column c = t.column(cid); if (c != null) n.add("`" + c.name + "`"); }
                    sb.append("- `").append(SqlExporter.indexName(t, idx)).append("`").append(idx.unique ? " (unique)" : "")
                            .append(": ").append(String.join(", ", n)).append('\n');
                }
            }
            List<String> out = new ArrayList<>(), in = new ArrayList<>();
            for (Relation r : d.relations) {
                Table f = d.table(r.fromTableId), to = d.table(r.toTableId);
                if (f == null || to == null) continue;
                String text = "`" + f.name + "." + f.column(r.fromColumnId).name + "` → [" + md(to.name) + "](#" + anchor(to.name) + ")`."
                        + to.column(r.toColumnId).name + "`" + (r.type == RelationType.ONE_TO_ONE ? " (1:1)" : "")
                        + (r.onDelete != null && r.onDelete != RefAction.NO_ACTION ? " · on delete " + r.onDelete.sql.toLowerCase(Locale.ROOT) : "");
                if (r.fromTableId.equals(t.id)) out.add(text);
                if (r.toTableId.equals(t.id) && !r.fromTableId.equals(t.id)) {
                    in.add("[" + md(f.name) + "](#" + anchor(f.name) + ")`." + f.column(r.fromColumnId).name + "` → `"
                            + t.name + "." + t.column(r.toColumnId).name + "`");
                }
            }
            if (!out.isEmpty()) {
                sb.append("\n**References**\n\n");
                for (String s : out) sb.append("- ").append(s).append('\n');
            }
            if (!in.isEmpty()) {
                sb.append("\n**Referenced by**\n\n");
                for (String s : in) sb.append("- ").append(s).append('\n');
            }
        }

        if (!d.notes.isEmpty()) {
            sb.append("\n## Notes\n\n");
            for (Note n : d.notes) sb.append("> ").append(md(n.text).replace("\n", "\n> ")).append("\n\n");
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String md(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    /** GitHub-style heading anchor. */
    static String anchor(String heading) {
        return heading.toLowerCase(Locale.ROOT).trim().replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-');
    }
}
