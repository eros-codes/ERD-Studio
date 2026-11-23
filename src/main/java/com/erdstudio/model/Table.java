package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Table {
    public String id = Ids.next();
    public String name = "table";
    public String color = Palette.DEFAULT_TABLE;
    public String comment = "";
    public double x;
    public double y;
    public List<Column> columns = new ArrayList<>();
    public List<TableIndex> indexes = new ArrayList<>();

    public Table() {}

    public Table(String name) {
        this.name = name;
    }

    public Column column(String columnId) {
        for (Column c : columns) if (c.id.equals(columnId)) return c;
        return null;
    }

    public Column columnByName(String columnName) {
        if (columnName == null) return null;
        for (Column c : columns) if (c.name.equalsIgnoreCase(columnName)) return c;
        return null;
    }

    public List<Column> primaryKey() {
        List<Column> pk = new ArrayList<>();
        for (Column c : columns) if (c.primaryKey) pk.add(c);
        return pk;
    }
}
