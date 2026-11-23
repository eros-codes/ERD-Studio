package com.erdstudio.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/** Stroke icons on a 24x24 grid (shapes after the MIT-licensed Feather set). Coloured via CSS (.icon). */
public final class Icons {
    private Icons() {}

    public static final String PLUS = "M12 5v14M5 12h14";
    public static final String TABLE = "M3 3h18v18H3zM3 9h18M3 15h18M9 9v12";
    public static final String NOTE = "M4 4h16v10l-6 6H4zM14 20v-6h6";
    public static final String LAYOUT = "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z";
    public static final String FIT = "M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3";
    public static final String UNDO = "M3 7v6h6M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13";
    public static final String REDO = "M21 7v6h-6M3 17a9 9 0 0 1 9-9 9 9 0 0 1 6 2.3L21 13";
    public static final String ZOOM_IN = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM21 21l-4.35-4.35M11 8v6M8 11h6";
    public static final String ZOOM_OUT = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM21 21l-4.35-4.35M8 11h6";
    public static final String SUN = "M12 7a5 5 0 1 0 0 10a5 5 0 1 0 0-10zM12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42";
    public static final String MOON = "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z";
    public static final String DOWNLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3";
    public static final String UPLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12";
    public static final String TRASH = "M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2";
    public static final String KEY = "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4";
    public static final String LINK = "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71";
    public static final String SEARCH = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM21 21l-4.35-4.35";
    public static final String CHEVRON_UP = "M18 15l-6-6-6 6";
    public static final String CHEVRON_DOWN = "M6 9l6 6 6-6";
    public static final String CHEVRON_RIGHT = "M9 18l6-6-6-6";
    public static final String X = "M18 6L6 18M6 6l12 12";
    public static final String COPY = "M9 9h11v11H9zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1";
    public static final String DATABASE = "M12 2c4.97 0 9 1.34 9 3s-4.03 3-9 3-9-1.34-9-3 4.03-3 9-3zM21 12c0 1.66-4 3-9 3s-9-1.34-9-3M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5";
    public static final String MAP = "M1 6v16l7-4 8 4 7-4V2l-7 4-8-4-7 4zM8 2v16M16 6v16";
    public static final String COMMENT = "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z";
    public static final String GRID = "M3 3h18v18H3zM3 12h18M12 3v18";
    public static final String IMAGE = "M3 3h18v18H3zM8.5 7a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3zM21 15l-5-5L5 21";
    public static final String SAVE = "M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2zM17 21v-8H7v8M7 3v5h8";
    public static final String FOLDER = "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z";
    public static final String FILE_PLUS = "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M12 18v-6M9 15h6";
    public static final String CODE = "M16 18l6-6-6-6M8 6l-6 6 6 6";
    public static final String SWAP = "M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3";
    public static final String INDEX = "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01";
    public static final String MORE = "M12 12h.01M19 12h.01M5 12h.01";
    public static final String KEYBOARD = "M2 6h20v12H2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10";
    public static final String MOUSE = "M12 2a6 6 0 0 0-6 6v8a6 6 0 0 0 12 0V8a6 6 0 0 0-6-6zM12 6v4";

    public static final String AREA = "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M8 9h8v6H8z";
    public static final String COMMAND = "M18 3a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3 3 3 0 0 0 3-3 3 3 0 0 0-3-3H6a3 3 0 0 0-3 3 3 3 0 0 0 3 3 3 3 0 0 0 3-3V6a3 3 0 0 0-3-3 3 3 0 0 0-3 3 3 3 0 0 0 3 3h12a3 3 0 0 0 3-3 3 3 0 0 0-3-3z";
    public static final String SERVER = "M2 2h20v8H2zM2 14h20v8H2zM6 6h.01M6 18h.01";
    public static final String HISTORY = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 3";
    public static final String SETTINGS = "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6";
    public static final String FILE = "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6";
    public static final String ALIGN_LEFT = "M3 3v18M7 6h10v4H7zM7 14h14v4H7z";
    public static final String ALIGN_HCENTER = "M12 3v18M7 6h10v4H7zM5 14h14v4H5z";
    public static final String ALIGN_RIGHT = "M21 3v18M7 6h10v4H7zM3 14h14v4H3z";
    public static final String ALIGN_TOP = "M3 3h18M6 7h4v10H6zM14 7h4v14h-4z";
    public static final String ALIGN_VCENTER = "M3 12h18M6 7h4v10H6zM14 5h4v14h-4z";
    public static final String ALIGN_BOTTOM = "M3 21h18M6 7h4v10H6zM14 3h4v14h-4z";
    public static final String DISTRIBUTE_H = "M4 3v18M20 3v18M10 8h4v8h-4z";
    public static final String DISTRIBUTE_V = "M3 4h18M3 20h18M8 10h8v4H8z";
    public static final String SPARKLES = "M12 3l1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9zM19 15l.9 2.1 2.1.9-2.1.9L19 21l-.9-2.1-2.1-.9 2.1-.9zM5 2l.7 1.6 1.6.7-1.6.7L5 6.6l-.7-1.6-1.6-.7 1.6-.7z";
    public static final String CAMERA = "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2zM12 9a4 4 0 1 0 0 8a4 4 0 1 0 0-8z";
    public static final String HOME = "M3 10l9-7 9 7v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM9 22V13h6v9";
    public static final String STAR = "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z";
    public static final String EDIT = "M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z";
    public static final String RESTORE = "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5";
    public static final String EXTERNAL = "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3";
    public static final String ZAP = "M13 2L3 14h9l-1 8 10-12h-9l1-8z";

    public static Node icon(String path, double size) {
        return icon(path, size, null);
    }

    public static Node icon(String path, double size, String extraClass) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("icon");
        double s = size / 24.0;
        svg.getTransforms().add(new Scale(s, s, 0, 0));
        Group g = new Group(svg);
        StackPane box = new StackPane(g);
        box.getStyleClass().add("icon-box");
        if (extraClass != null) box.getStyleClass().add(extraClass);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
