package com.erdstudio.model;

public enum RelationType {
    MANY_TO_ONE("Many to one"),
    ONE_TO_ONE("One to one");

    public final String label;

    RelationType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
