package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A foreign key: {@code from} is the referencing (child / FK) column,
 * {@code to} is the referenced (parent, usually PK) column.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Relation {
    public String id = Ids.next();
    public String name = "";
    public String fromTableId;
    public String fromColumnId;
    public String toTableId;
    public String toColumnId;
    public RelationType type = RelationType.MANY_TO_ONE;
    public RefAction onDelete = RefAction.NO_ACTION;
    public RefAction onUpdate = RefAction.NO_ACTION;

    public Relation() {}

    public Relation(Table from, Column fromCol, Table to, Column toCol) {
        this.fromTableId = from.id;
        this.fromColumnId = fromCol.id;
        this.toTableId = to.id;
        this.toColumnId = toCol.id;
    }

    public boolean touches(String tableId) {
        return tableId.equals(fromTableId) || tableId.equals(toTableId);
    }

    public boolean touchesColumn(String columnId) {
        return columnId.equals(fromColumnId) || columnId.equals(toColumnId);
    }
}
