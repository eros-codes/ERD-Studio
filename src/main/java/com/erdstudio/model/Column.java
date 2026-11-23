package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Column {
    public String id = Ids.next();
    public String name = "column";
    public String type = "INTEGER";
    public boolean primaryKey;
    public boolean notNull;
    public boolean unique;
    public boolean autoIncrement;
    public String defaultValue = "";
    public String comment = "";

    public Column() {}

    public Column(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public Column copy(boolean newId) {
        Column c = new Column(name, type);
        if (!newId) c.id = id;
        c.primaryKey = primaryKey;
        c.notNull = notNull;
        c.unique = unique;
        c.autoIncrement = autoIncrement;
        c.defaultValue = defaultValue;
        c.comment = comment;
        return c;
    }

    /** A column is effectively NOT NULL if flagged or if it is part of the primary key. */
    public boolean isRequired() {
        return notNull || primaryKey;
    }
}
