package com.erdstudio.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A coloured, named region that groups tables (like drawSQL areas / DBML TableGroups). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Area {
    public String id = Ids.next();
    public String name = "Area";
    public String color = Palette.AREA_COLORS[0];
    public double x;
    public double y;
    public double width = 480;
    public double height = 320;

    public boolean contains(double px, double py, double pw, double ph) {
        return px >= x && py >= y && px + pw <= x + width && py + ph <= y + height;
    }

    public Area copy(boolean newId) {
        Area a = new Area();
        if (!newId) a.id = id;
        a.name = name;
        a.color = color;
        a.x = x;
        a.y = y;
        a.width = width;
        a.height = height;
        return a;
    }
}
