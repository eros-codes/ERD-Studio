package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Note {
    public String id = Ids.next();
    public String text = "Note";
    public String color = Palette.NOTE_COLORS[0];
    public double x;
    public double y;
    public double width = 220;
    public double height = 130;

    public Note copy(boolean newId) {
        Note n = new Note();
        if (!newId) n.id = id;
        n.text = text;
        n.color = color;
        n.x = x;
        n.y = y;
        n.width = width;
        n.height = height;
        return n;
    }
}
