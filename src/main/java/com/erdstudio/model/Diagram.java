package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Diagram {
    public int formatVersion = 1;
    public String name = "Untitled diagram";
    public Dialect dialect = Dialect.POSTGRESQL;
    public List<Table> tables = new ArrayList<>();
    public List<Relation> relations = new ArrayList<>();
    public List<Note> notes = new ArrayList<>();
    public List<Area> areas = new ArrayList<>();
    public List<Version> versions = new ArrayList<>();

    /** Saved viewport, restored on open. */
    public double viewX = 0;
    public double viewY = 0;
    public double zoom = 1;

    public Table table(String id) {
        if (id == null) return null;
        for (Table t : tables) if (t.id.equals(id)) return t;
        return null;
    }

    public Table tableByName(String name) {
        if (name == null) return null;
        for (Table t : tables) if (t.name.equalsIgnoreCase(name)) return t;
        return null;
    }

    public Relation relation(String id) {
        for (Relation r : relations) if (r.id.equals(id)) return r;
        return null;
    }

    public Area area(String id) {
        for (Area a : areas) if (a.id.equals(id)) return a;
        return null;
    }

    public Note note(String id) {
        for (Note n : notes) if (n.id.equals(id)) return n;
        return null;
    }

    /** True when the column is the FK side of at least one relation. */
    public boolean isForeignKey(String columnId) {
        for (Relation r : relations) if (columnId.equals(r.fromColumnId)) return true;
        return false;
    }

    public List<Relation> relationsOf(String tableId) {
        List<Relation> out = new ArrayList<>();
        for (Relation r : relations) if (r.touches(tableId)) out.add(r);
        return out;
    }

    /** Removes relations whose tables or columns no longer exist. */
    public void pruneRelations() {
        relations.removeIf(r -> {
            Table f = table(r.fromTableId), t = table(r.toTableId);
            return f == null || t == null || f.column(r.fromColumnId) == null || t.column(r.toColumnId) == null;
        });
        for (Table t : tables) {
            for (TableIndex idx : t.indexes) idx.columnIds.removeIf(cid -> t.column(cid) == null);
            t.indexes.removeIf(idx -> idx.columnIds.isEmpty());
        }
    }

    public String uniqueTableName(String base) {
        if (tableByName(base) == null) return base;
        int i = 2;
        while (tableByName(base + "_" + i) != null) i++;
        return base + "_" + i;
    }
}
