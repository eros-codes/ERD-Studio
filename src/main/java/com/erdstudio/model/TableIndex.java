package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TableIndex {
    public String id = Ids.next();
    public String name = "";
    public boolean unique;
    /** Column ids, in index order. */
    public List<String> columnIds = new ArrayList<>();

    public TableIndex copy() {
        TableIndex i = new TableIndex();
        i.id = id;
        i.name = name;
        i.unique = unique;
        i.columnIds = new ArrayList<>(columnIds);
        return i;
    }
}
