package com.erdstudio.mcp;

import com.erdstudio.AppInfo;
import com.erdstudio.editor.Editor;
import com.erdstudio.editor.Settings;
import com.erdstudio.io.DbmlIO;
import com.erdstudio.io.DocsExporter;
import com.erdstudio.io.ImportResult;
import com.erdstudio.io.JsonStore;
import com.erdstudio.io.MigrationGenerator;
import com.erdstudio.io.PrismaIO;
import com.erdstudio.io.SqlExporter;
import com.erdstudio.io.SqlImporter;
import com.erdstudio.layout.AutoLayout;
import com.erdstudio.mcp.Backend.ProjectRef;
import com.erdstudio.mcp.Backend.ToolError;
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
import com.erdstudio.model.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The MCP tools: what an AI assistant may do with ERD Studio. Each call is one undo step in the app.
 * Destructive operations are limited to single tables, columns and relations; projects can never be
 * deleted from here. With "read-only" on in Settings, only the reading tools are offered.
 */
public final class McpTools {
    private McpTools() {}

    static final ObjectMapper JSON = new ObjectMapper();

    // ================================================================== tool catalogue

    private record Tool(String name, String description, ObjectNode schema, boolean writes) {}

    private static final List<Tool> TOOLS = new ArrayList<>();

    static {
        String project = "Project name (as shown on the ERD Studio home screen). Optional: defaults to the project open in the app, or the only project.";
        String column = "Column: {name, type, primary_key, not_null, unique, auto_increment, default, comment, references}. "
                + "'references' is 'table' or 'table.column' and creates a foreign key.";

        add("list_projects", "List the projects in the ERD Studio library (name, table count, database, whether open in the app).",
                false, obj());
        add("get_diagram", "Read a diagram: tables, columns (types, PK/NN/UQ/AI, defaults, comments), foreign keys, indexes and areas. "
                        + "format 'summary' (default, compact text) or 'json' (full model).",
                false, obj(p("project", "string", project), p("format", "string", "summary | json")));
        add("export_schema", "Generate code from a diagram: SQL DDL, Prisma schema, DBML, Mermaid erDiagram or Markdown docs.",
                false, obj(p("project", "string", project),
                        req(p("format", "string", "sql | prisma | dbml | mermaid | markdown")),
                        p("dialect", "string", "For sql/prisma: postgresql | mysql | sqlite | sqlserver (default: the project's)")));
        add("list_versions", "List saved versions (snapshots) of a project.", false, obj(p("project", "string", project)));
        add("generate_migration", "SQL migration (ALTER TABLE …) from a saved version to another version or to the current diagram.",
                false, obj(p("project", "string", project),
                        req(p("from_version", "string", "Name of the saved version to migrate from")),
                        p("to_version", "string", "Name of the target version (default: current diagram)"),
                        p("dialect", "string", "postgresql | mysql | sqlite | sqlserver")));

        add("create_project", "Create a new project in the library, optionally filled from SQL / Prisma / DBML.",
                true, obj(req(p("name", "string", "Project name")),
                        p("dialect", "string", "postgresql (default) | mysql | sqlite | sqlserver"),
                        p("schema", "string", "Optional SQL DDL, Prisma schema or DBML to import"),
                        p("format", "string", "auto (default) | sql | prisma | dbml")));
        add("import_schema", "Import SQL DDL, a Prisma schema or DBML into a project (merge next to existing tables, or replace everything).",
                true, obj(p("project", "string", project), req(p("schema", "string", "The schema text")),
                        p("format", "string", "auto (default) | sql | prisma | dbml"),
                        p("mode", "string", "merge (default) | replace")));
        add("add_table", "Add a table. If no columns are given an auto-increment primary key 'id' is created. "
                        + "Columns may reference other tables to create foreign keys.",
                true, obj(p("project", "string", project), req(p("name", "string", "Table name")),
                        arr("columns", column), p("comment", "string", "Table comment"),
                        p("area", "string", "Name of an existing area (group) to place the table in")));
        add("update_table", "Rename a table or change its comment / color.",
                true, obj(p("project", "string", project), req(p("table", "string", "Current table name")),
                        p("rename_to", "string", "New name"), p("comment", "string", "New comment"),
                        p("color", "string", "Hex color like #6366f1")));
        add("delete_table", "Delete one table (and the foreign keys that point to or from it).",
                true, obj(p("project", "string", project), req(p("table", "string", "Table name"))));
        add("add_column", "Add a column to a table.",
                true, obj(p("project", "string", project), req(p("table", "string", "Table name")),
                        req(objProp("column", column)), p("after", "string", "Insert after this column (default: at the end)")));
        add("update_column", "Change a column: name, type, flags, default or comment.",
                true, obj(p("project", "string", project), req(p("table", "string", "Table name")),
                        req(p("column", "string", "Current column name")),
                        req(objProp("changes", "Any of {name, type, primary_key, not_null, unique, auto_increment, default, comment}"))));
        add("delete_column", "Delete a column (and foreign keys that use it).",
                true, obj(p("project", "string", project), req(p("table", "string", "Table name")), req(p("column", "string", "Column name"))));
        add("add_relation", "Add a foreign key from_table.from_column → to_table.to_column. If from_column is omitted a "
                        + "'<to_table>_id' column is created; if to_column is omitted the target's primary key is used.",
                true, obj(p("project", "string", project), req(p("from_table", "string", "Referencing (child) table")),
                        p("from_column", "string", "FK column in the child table"),
                        req(p("to_table", "string", "Referenced (parent) table")), p("to_column", "string", "Referenced column"),
                        p("type", "string", "many_to_one (default) | one_to_one"),
                        p("on_delete", "string", "NO ACTION | CASCADE | SET NULL | SET DEFAULT | RESTRICT"),
                        p("on_update", "string", "NO ACTION | CASCADE | SET NULL | SET DEFAULT | RESTRICT")));
        add("delete_relation", "Delete the foreign key on from_table.from_column (the column itself stays).",
                true, obj(p("project", "string", project), req(p("from_table", "string", "Child table")), req(p("from_column", "string", "FK column"))));
        add("add_index", "Add an index (optionally unique) on one or more columns.",
                true, obj(p("project", "string", project), req(p("table", "string", "Table name")),
                        req(arrOf("columns", "string", "Column names")), p("unique", "boolean", "Unique index"),
                        p("name", "string", "Index name (optional)")));
        add("tidy_layout", "Re-arrange the diagram neatly (same as the Tidy up button).",
                true, obj(p("project", "string", project), p("style", "string", "left_to_right (default) | top_to_bottom | compact")));
        add("save_version", "Save a named snapshot of the current diagram (used for migrations and history).",
                true, obj(p("project", "string", project), req(p("name", "string", "Version name")), p("note", "string", "Optional note")));
    }

    private static void add(String name, String desc, boolean writes, ObjectNode schema) {
        TOOLS.add(new Tool(name, desc, schema, writes));
    }

    private static ObjectNode obj(ObjectNode... props) {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        ObjectNode ps = s.putObject("properties");
        ArrayNode required = JSON.createArrayNode();
        for (ObjectNode p : props) {
            String name = p.remove("__name").asText();
            if (p.has("__required")) { p.remove("__required"); required.add(name); }
            ps.set(name, p);
        }
        if (!required.isEmpty()) s.set("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    private static ObjectNode p(String name, String type, String desc) {
        ObjectNode n = JSON.createObjectNode();
        n.put("__name", name);
        n.put("type", type);
        n.put("description", desc);
        return n;
    }

    private static ObjectNode objProp(String name, String desc) {
        ObjectNode n = p(name, "object", desc);
        return n;
    }

    private static ObjectNode arr(String name, String itemDesc) {
        ObjectNode n = p(name, "array", itemDesc);
        n.putObject("items").put("type", "object");
        return n;
    }

    private static ObjectNode arrOf(String name, String itemType, String desc) {
        ObjectNode n = p(name, "array", desc);
        n.putObject("items").put("type", itemType);
        return n;
    }

    private static ObjectNode req(ObjectNode n) {
        n.put("__required", true);
        return n;
    }

    public static ArrayNode list() {
        ArrayNode out = JSON.createArrayNode();
        boolean ro = Settings.mcpReadOnly();
        for (Tool t : TOOLS) {
            if (ro && t.writes()) continue;
            ObjectNode n = out.addObject();
            n.put("name", t.name());
            n.put("description", t.description());
            n.set("inputSchema", t.schema().deepCopy());
            ObjectNode ann = n.putObject("annotations");
            ann.put("readOnlyHint", !t.writes());
            ann.put("destructiveHint", t.name().startsWith("delete_") || t.name().equals("import_schema"));
            ann.put("openWorldHint", false);
        }
        return out;
    }

    // ================================================================== dispatch

    /** Runs a tool and returns its text result. Throws ToolError with a message meant for the model. */
    public static String call(String name, JsonNode args, Backend b) throws ToolError {
        final JsonNode a = args == null || args.isNull() ? JSON.createObjectNode() : args;
        Tool tool = null;
        for (Tool t : TOOLS) if (t.name().equals(name)) tool = t;
        if (tool == null) throw new ToolError("Unknown tool: " + name);
        if (tool.writes() && Settings.mcpReadOnly())
            throw new ToolError("ERD Studio is in read-only mode for AI assistants (Help → Connect an AI assistant). Ask the user to allow editing.");
        String project = str(a, "project");
        return switch (name) {
            case "list_projects" -> listProjects(b);
            case "get_diagram" -> getDiagram(b.read(project), str(a, "format"));
            case "export_schema" -> export(b.read(project), need(a, "format"), str(a, "dialect"));
            case "list_versions" -> listVersions(b.read(project));
            case "generate_migration" -> migration(b.read(project), need(a, "from_version"), str(a, "to_version"), str(a, "dialect"));
            case "create_project" -> createProject(b, a);
            case "import_schema" -> b.edit(project, (ed, size, touched) -> importSchema(ed, size, touched, a));
            case "add_table" -> b.edit(project, (ed, size, touched) -> addTable(ed, size, touched, a));
            case "update_table" -> b.edit(project, (ed, size, touched) -> updateTable(ed, touched, a));
            case "delete_table" -> b.edit(project, (ed, size, touched) -> deleteTable(ed, a));
            case "add_column" -> b.edit(project, (ed, size, touched) -> addColumn(ed, touched, a));
            case "update_column" -> b.edit(project, (ed, size, touched) -> updateColumn(ed, touched, a));
            case "delete_column" -> b.edit(project, (ed, size, touched) -> deleteColumn(ed, touched, a));
            case "add_relation" -> b.edit(project, (ed, size, touched) -> addRelation(ed, touched, a));
            case "delete_relation" -> b.edit(project, (ed, size, touched) -> deleteRelation(ed, touched, a));
            case "add_index" -> b.edit(project, (ed, size, touched) -> addIndex(ed, touched, a));
            case "tidy_layout" -> b.edit(project, (ed, size, touched) -> tidy(ed, size, a));
            case "save_version" -> b.edit(project, (ed, size, touched) -> saveVersion(ed, a));
            default -> throw new ToolError("Unknown tool: " + name);
        };
    }

    // ================================================================== reading

    private static String listProjects(Backend b) throws ToolError {
        List<ProjectRef> ps = b.projects();
        if (ps.isEmpty()) return "The library is empty. Use create_project to start one.";
        StringBuilder sb = new StringBuilder();
        sb.append(ps.size()).append(" project(s)").append(b.live() ? " — ERD Studio is open; changes appear live on the canvas" : " — ERD Studio is closed; changes are written to the project files").append(":\n");
        for (ProjectRef p : ps) {
            sb.append("- ").append(p.name()).append("  (").append(p.tables()).append(" tables, ").append(p.dialect());
            if (p.open()) sb.append(", open in app");
            sb.append(")\n");
        }
        return sb.toString();
    }

    static String getDiagram(Diagram d, String format) {
        if ("json".equalsIgnoreCase(format)) {
            Diagram copy = JsonStore.fromJson(JsonStore.toJson(d), Diagram.class);
            copy.versions = new ArrayList<>();
            return JsonStore.toJson(copy);
        }
        return summary(d);
    }

    static String summary(Diagram d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(d.name).append(" (").append(d.dialect.label).append(") — ")
                .append(d.tables.size()).append(" tables, ").append(d.relations.size()).append(" foreign keys\n");
        Map<String, String> areaOf = areaOf(d);
        Map<String, List<Relation>> fks = new LinkedHashMap<>();
        for (Relation r : d.relations) fks.computeIfAbsent(r.fromColumnId, k -> new ArrayList<>()).add(r);
        for (Table t : d.tables) {
            sb.append("\nTable ").append(t.name);
            if (areaOf.containsKey(t.id)) sb.append("  [area: ").append(areaOf.get(t.id)).append("]");
            if (t.comment != null && !t.comment.isBlank()) sb.append("  -- ").append(t.comment.replace("\n", " "));
            sb.append('\n');
            for (Column c : t.columns) {
                sb.append("  ").append(c.name).append(' ').append(c.type == null ? "?" : c.type);
                if (c.primaryKey) sb.append(" PK");
                if (c.autoIncrement) sb.append(" AI");
                if (c.notNull && !c.primaryKey) sb.append(" NOT NULL");
                if (c.unique) sb.append(" UNIQUE");
                if (c.defaultValue != null && !c.defaultValue.isBlank()) sb.append(" DEFAULT ").append(c.defaultValue);
                for (Relation r : fks.getOrDefault(c.id, List.of())) {
                    Table to = d.table(r.toTableId);
                    Column tc = to == null ? null : to.column(r.toColumnId);
                    if (to != null && tc != null) {
                        sb.append(" -> ").append(to.name).append('.').append(tc.name);
                        if (r.type == RelationType.ONE_TO_ONE) sb.append(" (1:1)");
                        if (r.onDelete != RefAction.NO_ACTION) sb.append(" ON DELETE ").append(r.onDelete.sql);
                        if (r.onUpdate != RefAction.NO_ACTION) sb.append(" ON UPDATE ").append(r.onUpdate.sql);
                    }
                }
                if (c.comment != null && !c.comment.isBlank()) sb.append("  -- ").append(c.comment.replace("\n", " "));
                sb.append('\n');
            }
            for (TableIndex idx : t.indexes) {
                List<String> names = new ArrayList<>();
                for (String cid : idx.columnIds) { Column c = t.column(cid); if (c != null) names.add(c.name); }
                sb.append("  ").append(idx.unique ? "UNIQUE INDEX " : "INDEX ")
                        .append(idx.name == null || idx.name.isBlank() ? "" : idx.name + " ").append("(").append(String.join(", ", names)).append(")\n");
            }
        }
        if (!d.areas.isEmpty()) {
            sb.append("\nAreas: ");
            List<String> n = new ArrayList<>();
            for (Area a : d.areas) n.add(a.name);
            sb.append(String.join(", ", n)).append('\n');
        }
        if (!d.notes.isEmpty()) {
            sb.append("\nNotes:\n");
            for (Note n : d.notes) sb.append("- ").append(n.text.replace("\n", " ")).append('\n');
        }
        return sb.toString();
    }

    /** Which area each table sits in (by position, like the canvas). */
    static Map<String, String> areaOf(Diagram d) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Table t : d.tables) {
            for (Area a : d.areas) {
                if (a.contains(t.x, t.y, Math.min(Sizes.width(t), a.width), Math.min(Sizes.height(t), a.height))
                        || (t.x >= a.x && t.y >= a.y && t.x < a.x + a.width && t.y < a.y + a.height)) {
                    out.put(t.id, a.name);
                    break;
                }
            }
        }
        return out;
    }

    static Map<String, List<String>> areaMembers(Diagram d) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, String> areaOf = areaOf(d);
        for (Area a : d.areas) {
            List<String> ids = new ArrayList<>();
            for (Table t : d.tables) if (a.name.equals(areaOf.get(t.id))) ids.add(t.id);
            out.put(a.id, ids);
        }
        return out;
    }

    private static String export(Diagram d, String format, String dialect) throws ToolError {
        Dialect dl = dialect == null ? d.dialect : dialect(dialect);
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "sql" -> SqlExporter.export(d, dl, SqlExporter.Options.defaults());
            case "prisma" -> PrismaIO.export(d, new PrismaIO.Options(true, dl));
            case "dbml" -> DbmlIO.export(d, areaMembers(d));
            case "mermaid" -> DocsExporter.mermaid(d);
            case "markdown", "md" -> DocsExporter.markdown(d, areaMembers(d), true);
            default -> throw new ToolError("Unknown format '" + format + "'. Use sql, prisma, dbml, mermaid or markdown.");
        };
    }

    private static String listVersions(Diagram d) {
        if (d.versions.isEmpty()) return "No saved versions in \"" + d.name + "\". Use save_version to create one.";
        StringBuilder sb = new StringBuilder("Versions of \"" + d.name + "\" (oldest first):\n");
        for (Version v : d.versions) {
            sb.append("- ").append(v.name).append("  (").append(v.createdAt).append(")");
            if (v.note != null && !v.note.isBlank()) sb.append(" — ").append(v.note);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String migration(Diagram d, String from, String to, String dialect) throws ToolError {
        Version vf = version(d, from);
        Diagram a = Editor.versionDiagram(vf);
        Diagram b;
        String toLabel;
        if (to == null || to.isBlank()) {
            b = JsonStore.fromJson(JsonStore.toJson(d), Diagram.class);
            toLabel = "current";
        } else {
            Version vt = version(d, to);
            b = Editor.versionDiagram(vt);
            toLabel = vt.name;
        }
        Dialect dl = dialect == null ? d.dialect : dialect(dialect);
        return MigrationGenerator.generate(a, b, dl, vf.name, toLabel);
    }

    private static Version version(Diagram d, String name) throws ToolError {
        for (Version v : d.versions) if (v.name.equalsIgnoreCase(name)) return v;
        List<String> names = new ArrayList<>();
        for (Version v : d.versions) names.add(v.name);
        throw new ToolError("No version named '" + name + "'. Saved versions: " + (names.isEmpty() ? "none" : String.join(", ", names)));
    }

    // ================================================================== writing

    private static String createProject(Backend b, JsonNode a) throws ToolError {
        String name = need(a, "name");
        Dialect dl = str(a, "dialect") == null ? Settings.defaultDialect() : dialect(str(a, "dialect"));
        String created = b.create(name, dl);
        String schema = str(a, "schema");
        if (schema == null || schema.isBlank()) return "Created project \"" + created + "\" (" + dl.label + ").";
        ObjectNode args = JSON.createObjectNode();
        args.put("schema", schema);
        args.put("format", str(a, "format") == null ? "auto" : str(a, "format"));
        args.put("mode", "replace");
        String r = b.edit(created, (ed, size, touched) -> importSchema(ed, size, touched, args));
        return "Created project \"" + created + "\". " + r;
    }

    private static String importSchema(Editor ed, Editor.Sizer size, Set<String> touched, JsonNode a) throws ToolError {
        String text = need(a, "schema");
        String fmt = str(a, "format") == null ? "auto" : str(a, "format").toLowerCase(Locale.ROOT);
        boolean replace = "replace".equalsIgnoreCase(str(a, "mode"));
        Diagram d = ed.diagram();
        ImportResult r;
        try {
            r = switch (fmt.equals("auto") ? detect(text) : fmt) {
                case "prisma" -> PrismaIO.parse(text);
                case "dbml" -> DbmlIO.parse(text);
                case "sql" -> SqlImporter.parse(text, d.dialect);
                default -> throw new ToolError("Unknown format '" + fmt + "'. Use auto, sql, prisma or dbml.");
            };
        } catch (ToolError e) {
            throw e;
        } catch (Exception e) {
            throw new ToolError("Could not parse the schema: " + e.getMessage());
        }
        if (r.tableCount() == 0)
            throw new ToolError("No tables found." + (r.warnings().isEmpty() ? "" : " Parser said: " + String.join("; ", r.warnings())));
        String keepName = d.name;
        Set<String> ids = ed.applyImport(r, replace, size);
        ed.diagram().name = keepName;
        touched.addAll(ids);
        String w = r.warnings().isEmpty() ? "" : "\nWarnings:\n- " + String.join("\n- ", r.warnings());
        return (replace ? "Replaced the diagram with " : "Imported ") + r.tableCount() + " table(s)" + w;
    }

    static String detect(String s) {
        if (Pattern.compile("(?m)^\\s*(model|datasource|generator)\\s+\\w+\\s*\\{").matcher(s).find()) return "prisma";
        if (Pattern.compile("(?im)^\\s*(Table|Ref|TableGroup|Enum|Project)\\b[^\\n;(]*(\\{|:)").matcher(s).find()
                && !Pattern.compile("(?i)CREATE\\s+TABLE").matcher(s).find()) return "dbml";
        return "sql";
    }

    private static String addTable(Editor ed, Editor.Sizer size, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        String name = need(a, "name").trim();
        if (d.tableByName(name) != null) throw new ToolError("A table named '" + name + "' already exists.");
        Table t = new Table(name);
        t.color = Palette.tableColor(d.tables.size());
        t.comment = str(a, "comment") == null ? "" : str(a, "comment");
        List<String[]> refs = new ArrayList<>();
        JsonNode cols = a.get("columns");
        if (cols != null && cols.isArray() && !cols.isEmpty()) {
            for (JsonNode cn : cols) {
                Column c = columnFrom(cn, d.dialect, null);
                if (t.columnByName(c.name) != null) throw new ToolError("Duplicate column '" + c.name + "'.");
                t.columns.add(c);
                String ref = str(cn, "references");
                if (ref != null && !ref.isBlank()) refs.add(new String[]{c.name, ref});
            }
        } else {
            Column id = new Column(Settings.idColumnName(), d.dialect.defaultIntType());
            id.primaryKey = id.notNull = id.autoIncrement = true;
            t.columns.add(id);
        }
        place(d, t, str(a, "area"), size);
        d.tables.add(t);
        touched.add(t.id);
        StringBuilder sb = new StringBuilder("Added table " + name + " with " + t.columns.size() + " column(s).");
        for (String[] r : refs) {
            String[] tc = r[1].split("\\.", 2);
            Relation rel = relate(d, t, t.columnByName(r[0]), table(d, tc[0]), tc.length > 1 ? tc[1] : null);
            touched.add(rel.id);
            sb.append("\nForeign key ").append(name).append('.').append(r[0]).append(" -> ").append(d.table(rel.toTableId).name)
                    .append('.').append(d.table(rel.toTableId).column(rel.toColumnId).name);
        }
        ed.commit();
        return sb.toString();
    }

    /** Puts a new table in a free spot: inside the named area, or to the right of the existing tables. */
    private static void place(Diagram d, Table t, String areaName, Editor.Sizer size) throws ToolError {
        double w = size.apply(t).width(), h = size.apply(t).height();
        if (areaName != null && !areaName.isBlank()) {
            Area area = null;
            for (Area ar : d.areas) if (ar.name.equalsIgnoreCase(areaName)) area = ar;
            if (area == null) throw new ToolError("No area named '" + areaName + "'.");
            double x = area.x + 30, y = area.y + 56;
            for (int guard = 0; guard < 200 && overlaps(d, x, y, w, h, size); guard++) {
                x += 60;
                if (x + w > area.x + area.width - 20) { x = area.x + 30; y += 60; }
            }
            t.x = Math.round(x);
            t.y = Math.round(y);
            if (t.x + w + 30 > area.x + area.width) area.width = t.x + w + 30 - area.x;
            if (t.y + h + 30 > area.y + area.height) area.height = t.y + h + 30 - area.y;
            return;
        }
        if (d.tables.isEmpty()) { t.x = 80; t.y = 80; return; }
        double maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (Table o : d.tables) {
            maxX = Math.max(maxX, o.x + size.apply(o).width());
            minY = Math.min(minY, o.y);
        }
        double x = maxX + 90, y = minY;
        for (int guard = 0; guard < 200 && overlaps(d, x, y, w, h, size); guard++) y += 60;
        t.x = Math.round(x);
        t.y = Math.round(y);
    }

    private static boolean overlaps(Diagram d, double x, double y, double w, double h, Editor.Sizer size) {
        for (Table o : d.tables) {
            AutoLayout.Size s = size.apply(o);
            if (x < o.x + s.width() + 30 && x + w + 30 > o.x && y < o.y + s.height() + 30 && y + h + 30 > o.y) return true;
        }
        return false;
    }

    private static String updateTable(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table t = table(d, need(a, "table"));
        List<String> changes = new ArrayList<>();
        String rn = str(a, "rename_to");
        if (rn != null && !rn.isBlank() && !rn.equals(t.name)) {
            Table other = d.tableByName(rn);
            if (other != null && other != t) throw new ToolError("A table named '" + rn + "' already exists.");
            changes.add("renamed " + t.name + " → " + rn);
            t.name = rn.trim();
        }
        if (a.has("comment")) { t.comment = str(a, "comment") == null ? "" : str(a, "comment"); changes.add("comment"); }
        String color = str(a, "color");
        if (color != null) {
            if (!color.matches("#[0-9a-fA-F]{6}")) throw new ToolError("Color must look like #6366f1.");
            t.color = color;
            changes.add("color");
        }
        if (changes.isEmpty()) return "Nothing to change.";
        touched.add(t.id);
        ed.commit();
        return "Updated table " + t.name + ": " + String.join(", ", changes) + ".";
    }

    private static String deleteTable(Editor ed, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table t = table(d, need(a, "table"));
        int before = d.relations.size();
        d.tables.remove(t);
        d.pruneRelations();
        ed.clearSelection();
        ed.commit();
        int removed = before - d.relations.size();
        return "Deleted table " + t.name + (removed > 0 ? " and " + removed + " foreign key(s)" : "") + ". (Undo with Ctrl+Z in the app.)";
    }

    private static String addColumn(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table t = table(d, need(a, "table"));
        JsonNode cn = a.get("column");
        if (cn == null || !cn.isObject()) throw new ToolError("'column' must be an object like {\"name\": \"email\", \"type\": \"VARCHAR(255)\"}.");
        Column c = columnFrom(cn, d.dialect, null);
        if (t.columnByName(c.name) != null) throw new ToolError("Table " + t.name + " already has a column '" + c.name + "'.");
        String after = str(a, "after");
        int at = t.columns.size();
        if (after != null) {
            Column ac = column(t, after);
            at = t.columns.indexOf(ac) + 1;
        }
        t.columns.add(at, c);
        touched.add(t.id);
        String msg = "Added column " + t.name + "." + c.name + " " + c.type + ".";
        String ref = str(cn, "references");
        if (ref != null && !ref.isBlank()) {
            String[] tc = ref.split("\\.", 2);
            Relation r = relate(d, t, c, table(d, tc[0]), tc.length > 1 ? tc[1] : null);
            touched.add(r.id);
            msg += " Foreign key → " + d.table(r.toTableId).name + "." + d.table(r.toTableId).column(r.toColumnId).name + ".";
        }
        ed.commit();
        return msg;
    }

    private static String updateColumn(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table t = table(d, need(a, "table"));
        Column c = column(t, need(a, "column"));
        JsonNode ch = a.get("changes");
        if (ch == null || !ch.isObject() || ch.isEmpty()) throw new ToolError("'changes' must be an object with the fields to change.");
        String newName = str(ch, "name");
        if (newName != null && !newName.equalsIgnoreCase(c.name) && t.columnByName(newName) != null)
            throw new ToolError("Table " + t.name + " already has a column '" + newName + "'.");
        String old = c.name;
        applyColumnFields(c, ch);
        touched.add(t.id);
        ed.commit();
        return "Updated " + t.name + "." + old + (old.equals(c.name) ? "" : " (now " + c.name + ")") + ": " + c.type
                + (c.primaryKey ? " PK" : "") + (c.notNull && !c.primaryKey ? " NOT NULL" : "") + (c.unique ? " UNIQUE" : "") + ".";
    }

    private static String deleteColumn(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table t = table(d, need(a, "table"));
        Column c = column(t, need(a, "column"));
        int before = d.relations.size();
        t.columns.remove(c);
        d.pruneRelations();
        touched.add(t.id);
        ed.commit();
        int removed = before - d.relations.size();
        return "Deleted column " + t.name + "." + c.name + (removed > 0 ? " and " + removed + " foreign key(s)" : "") + ".";
    }

    private static String addRelation(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table from = table(d, need(a, "from_table"));
        Table to = table(d, need(a, "to_table"));
        String fc = str(a, "from_column");
        Column fromCol;
        boolean created = false;
        if (fc == null || fc.isBlank()) {
            Column target = targetColumn(to, str(a, "to_column"));
            String base = Settings.fkPattern().replace("{table}", singular(to.name));
            String n = base;
            for (int i = 2; from.columnByName(n) != null; i++) n = base + "_" + i;
            fromCol = new Column(n, Editor.fkType(target));
            String od = str(a, "on_delete");
            fromCol.notNull = od == null || !od.trim().toUpperCase(Locale.ROOT).replace('_', ' ').equals("SET NULL");
            from.columns.add(fromCol);
            created = true;
        } else {
            fromCol = column(from, fc);
        }
        Relation r = relate(d, from, fromCol, to, str(a, "to_column"));
        if (str(a, "type") != null) r.type = "one_to_one".equalsIgnoreCase(str(a, "type").replace('-', '_')) ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
        if (str(a, "on_delete") != null) r.onDelete = action(str(a, "on_delete"));
        if (str(a, "on_update") != null) r.onUpdate = action(str(a, "on_update"));
        touched.add(r.id);
        touched.add(from.id);
        ed.commit();
        Column tc = to.column(r.toColumnId);
        return "Added foreign key " + from.name + "." + fromCol.name + " -> " + to.name + "." + tc.name
                + (created ? " (created column " + fromCol.name + " " + fromCol.type + ")" : "") + ".";
    }

    private static String deleteRelation(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table from = table(d, need(a, "from_table"));
        Column c = column(from, need(a, "from_column"));
        boolean removed = d.relations.removeIf(r -> r.fromColumnId.equals(c.id));
        if (!removed) throw new ToolError(from.name + "." + c.name + " has no foreign key.");
        touched.add(from.id);
        ed.commit();
        return "Removed the foreign key on " + from.name + "." + c.name + " (the column is kept).";
    }

    private static String addIndex(Editor ed, Set<String> touched, JsonNode a) throws ToolError {
        Diagram d = ed.diagram();
        Table t = table(d, need(a, "table"));
        JsonNode cols = a.get("columns");
        if (cols == null || !cols.isArray() || cols.isEmpty()) throw new ToolError("'columns' must list at least one column name.");
        TableIndex idx = new TableIndex();
        idx.unique = a.path("unique").asBoolean(false);
        idx.name = str(a, "name") == null ? "" : str(a, "name");
        List<String> names = new ArrayList<>();
        for (JsonNode n : cols) {
            Column c = column(t, n.asText());
            idx.columnIds.add(c.id);
            names.add(c.name);
        }
        t.indexes.add(idx);
        touched.add(t.id);
        ed.commit();
        return "Added " + (idx.unique ? "unique " : "") + "index on " + t.name + " (" + String.join(", ", names) + ").";
    }

    private static String tidy(Editor ed, Editor.Sizer size, JsonNode a) throws ToolError {
        String s = str(a, "style");
        AutoLayout.Style st = Settings.tidyStyle();
        if (s != null) {
            st = switch (s.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
                case "left_to_right", "lr", "horizontal" -> AutoLayout.Style.LEFT_TO_RIGHT;
                case "top_to_bottom", "tb", "vertical" -> AutoLayout.Style.TOP_TO_BOTTOM;
                case "compact" -> AutoLayout.Style.COMPACT;
                default -> throw new ToolError("Unknown style '" + s + "'. Use left_to_right, top_to_bottom or compact.");
            };
        }
        ed.tidy(size, st);
        return "Tidied the layout (" + st.label + ").";
    }

    private static String saveVersion(Editor ed, JsonNode a) throws ToolError {
        String name = need(a, "name");
        for (Version v : ed.diagram().versions)
            if (v.name.equalsIgnoreCase(name)) throw new ToolError("A version named '" + name + "' already exists.");
        Version v = ed.saveVersion(name, str(a, "note"));
        return "Saved version \"" + v.name + "\".";
    }

    // ================================================================== helpers

    private static Column columnFrom(JsonNode n, Dialect dl, Column base) throws ToolError {
        String name = str(n, "name");
        if (name == null || name.isBlank()) throw new ToolError("Every column needs a 'name'.");
        Column c = base != null ? base : new Column(name.trim(), dl.defaultTextType());
        applyColumnFields(c, n);
        if (c.primaryKey) c.notNull = true;
        return c;
    }

    private static void applyColumnFields(Column c, JsonNode n) {
        if (str(n, "name") != null && !str(n, "name").isBlank()) c.name = str(n, "name").trim();
        if (str(n, "type") != null && !str(n, "type").isBlank()) c.type = str(n, "type").trim();
        if (n.has("primary_key")) c.primaryKey = n.get("primary_key").asBoolean();
        if (n.has("not_null")) c.notNull = n.get("not_null").asBoolean();
        if (n.has("nullable")) c.notNull = !n.get("nullable").asBoolean();
        if (n.has("unique")) c.unique = n.get("unique").asBoolean();
        if (n.has("auto_increment")) c.autoIncrement = n.get("auto_increment").asBoolean();
        if (n.has("default")) c.defaultValue = n.get("default").isNull() ? "" : n.get("default").asText();
        if (n.has("comment")) c.comment = n.get("comment").isNull() ? "" : n.get("comment").asText();
        if (c.primaryKey) c.notNull = true;
    }

    private static Relation relate(Diagram d, Table from, Column fromCol, Table to, String toColumn) throws ToolError {
        Column target = targetColumn(to, toColumn);
        for (Relation r : d.relations)
            if (r.fromColumnId.equals(fromCol.id) && r.toColumnId.equals(target.id)) return r;
        Relation r = new Relation(from, fromCol, to, target);
        r.type = fromCol.unique ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
        d.relations.add(r);
        return r;
    }

    private static Column targetColumn(Table to, String name) throws ToolError {
        if (name != null && !name.isBlank()) return column(to, name);
        List<Column> pk = to.primaryKey();
        if (pk.isEmpty()) throw new ToolError("Table " + to.name + " has no primary key; give to_column explicitly.");
        return pk.get(0);
    }

    static Table table(Diagram d, String name) throws ToolError {
        if (name == null) throw new ToolError("Missing table name.");
        Table t = d.tableByName(name.trim());
        if (t != null) return t;
        List<String> names = new ArrayList<>();
        for (Table x : d.tables) names.add(x.name);
        throw new ToolError("No table named '" + name + "' in \"" + d.name + "\". Tables: " + (names.isEmpty() ? "none" : String.join(", ", names)));
    }

    static Column column(Table t, String name) throws ToolError {
        Column c = t.columnByName(name == null ? null : name.trim());
        if (c != null) return c;
        List<String> names = new ArrayList<>();
        for (Column x : t.columns) names.add(x.name);
        throw new ToolError("Table " + t.name + " has no column '" + name + "'. Columns: " + String.join(", ", names));
    }

    static Dialect dialect(String s) throws ToolError {
        String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (k) {
            case "postgresql", "postgres", "pg", "psql" -> Dialect.POSTGRESQL;
            case "mysql", "mariadb", "mysqlmariadb" -> Dialect.MYSQL;
            case "sqlite", "sqlite3" -> Dialect.SQLITE;
            case "sqlserver", "mssql", "tsql", "sqlsrv" -> Dialect.SQLSERVER;
            default -> throw new ToolError("Unknown database '" + s + "'. Use postgresql, mysql, sqlite or sqlserver.");
        };
    }

    private static RefAction action(String s) throws ToolError {
        String t = s.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        for (RefAction a : RefAction.values()) if (a.sql.equals(t)) return a;
        throw new ToolError("Unknown action '" + s + "'. Use NO ACTION, CASCADE, SET NULL, SET DEFAULT or RESTRICT.");
    }

    static String singular(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.endsWith("ies") && name.length() > 4) return name.substring(0, name.length() - 3) + "y";
        if (l.endsWith("sses") || l.endsWith("xes") || l.endsWith("ches") || l.endsWith("shes")) return name.substring(0, name.length() - 2);
        if (l.endsWith("s") && !l.endsWith("ss") && name.length() > 3) return name.substring(0, name.length() - 1);
        return name;
    }

    static String str(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String need(JsonNode n, String key) throws ToolError {
        String v = str(n, key);
        if (v == null || v.isBlank()) throw new ToolError("Missing required argument '" + key + "'.");
        return v;
    }

    static String serverInstructions() {
        return "ERD Studio " + AppInfo.VERSION + " is a database diagram designer. Use list_projects first, then get_diagram "
                + "to read a schema before changing it. Every edit is one undo step in the app; when ERD Studio is open, "
                + "changes appear live on the user's canvas. Prefer import_schema for many tables at once and finish "
                + "bigger changes with tidy_layout.";
    }
}
