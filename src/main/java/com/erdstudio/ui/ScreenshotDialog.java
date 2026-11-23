package com.erdstudio.ui;

import com.erdstudio.canvas.DiagramCanvas;
import com.erdstudio.model.Diagram;
import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.time.LocalDate;
import java.util.Set;

/** High-resolution, presentation-ready screenshots of the diagram. */
public final class ScreenshotDialog extends Dialog<Void> {

    public enum Scope {
        ALL("Whole diagram"), SELECTION("Selection"), VIEW("Visible area");
        final String label;
        Scope(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Background {
        FRAME("Gradient frame"), CANVAS("Canvas"), WHITE("White"), TRANSPARENT("Transparent");
        final String label;
        Background(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Look {
        CURRENT("Current theme"), LIGHT("Light"), DARK("Dark");
        final String label;
        Look(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Frame {
        INDIGO("Indigo", "#818cf8", "#6366f1", "#a855f7"),
        OCEAN("Ocean", "#22d3ee", "#3b82f6", "#1e3a8a"),
        SUNSET("Sunset", "#fb7185", "#f97316", "#facc15"),
        MINT("Mint", "#34d399", "#14b8a6", "#0e7490"),
        GRAPHITE("Graphite", "#4b5563", "#1f2937", "#030712"),
        PAPER("Paper", "#f8fafc", "#e2e8f0", "#cbd5e1");
        final String label, a, b, c;
        Frame(String l, String a, String b, String c) { label = l; this.a = a; this.b = b; this.c = c; }
        @Override public String toString() { return label; }
    }

    public record Options(Scope scope, double scale, Look look, Background background, Frame frame, boolean grid,
                          double padding, boolean title, String titleText, String subtitle) {}

    private static final double MAX_SIDE = 16000;

    private final DiagramCanvas canvas;
    private final Diagram diagram;
    private final ImageView preview = new ImageView();
    private final Label sizeLabel = new Label();
    private final Label status = new Label();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(180));

    private final ComboBox<Scope> scope = new ComboBox<>();
    private final ToggleGroup scaleGroup = new ToggleGroup();
    private final ComboBox<Look> look = new ComboBox<>();
    private final ComboBox<Background> background = new ComboBox<>();
    private final ComboBox<Frame> frame = new ComboBox<>();
    private final CheckBox grid = new CheckBox("Dot grid");
    private final Slider padding = new Slider(0, 200, 48);
    private final CheckBox title = new CheckBox("Title");
    private final TextField titleText = new TextField();
    private final TextField subtitle = new TextField();
    private Button save, copy;

    public ScreenshotDialog(Window owner, DiagramCanvas canvas, Diagram diagram, boolean hasSelection) {
        this.canvas = canvas;
        this.diagram = diagram;
        initOwner(owner);
        setTitle("Screenshot");
        setHeaderText("High-quality screenshot");
        setResizable(true);
        Theme.apply(this);

        scope.getItems().setAll(Scope.values());
        scope.setValue(hasSelection ? Scope.SELECTION : Scope.ALL);
        HBox scales = new HBox(4);
        scales.getStyleClass().add("segmented");
        for (int s : new int[]{1, 2, 3, 4}) {
            ToggleButton b = new ToggleButton(s + "×");
            b.setUserData((double) s);
            b.setToggleGroup(scaleGroup);
            if (s == 2) b.setSelected(true);
            scales.getChildren().add(b);
        }
        scaleGroup.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); });
        look.getItems().setAll(Look.values());
        look.setValue(Look.CURRENT);
        background.getItems().setAll(Background.values());
        background.setValue(Background.FRAME);
        frame.getItems().setAll(Frame.values());
        frame.setValue(Frame.INDIGO);
        grid.setSelected(true);
        title.setSelected(true);
        titleText.setText(diagram.name);
        subtitle.setText(diagram.dialect.label + "  ·  " + diagram.tables.size() + " tables  ·  " + LocalDate.now());
        padding.setShowTickLabels(false);
        padding.setPrefWidth(160);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        int r = 0;
        g.addRow(r++, label("Capture"), scope);
        g.addRow(r++, label("Quality"), scales);
        g.addRow(r++, label("Theme"), look);
        g.addRow(r++, label("Background"), background);
        g.addRow(r++, label("Frame colors"), frame);
        g.addRow(r++, label("Margin"), padding);
        g.add(grid, 1, r++);
        g.add(title, 1, r++);
        g.add(titleText, 1, r++);
        g.add(subtitle, 1, r++);
        titleText.setPromptText("Title");
        subtitle.setPromptText("Subtitle");
        sizeLabel.getStyleClass().add("hint");
        status.getStyleClass().add("hint");
        status.setWrapText(true);
        VBox controls = new VBox(14, g, sizeLabel, status);
        controls.setPrefWidth(330);

        preview.setPreserveRatio(true);
        preview.setSmooth(true);
        StackPane previewBox = new StackPane(preview);
        previewBox.getStyleClass().add("shot-preview");
        previewBox.setPrefSize(720, 520);
        previewBox.setMinSize(420, 320);
        preview.fitWidthProperty().bind(previewBox.widthProperty().subtract(24));
        preview.fitHeightProperty().bind(previewBox.heightProperty().subtract(24));
        HBox.setHgrow(previewBox, Priority.ALWAYS);

        copy = new Button("Copy to clipboard");
        copy.setGraphic(Icons.icon(Icons.COPY, 15));
        copy.setOnAction(e -> {
            WritableImage img = render(options(), false);
            if (img == null) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putImage(img);
            Clipboard.getSystemClipboard().setContent(cc);
            status.setText("Copied " + (int) img.getWidth() + " × " + (int) img.getHeight() + " px to the clipboard.");
        });
        save = new Button("Save PNG…");
        save.getStyleClass().add("primary");
        save.setGraphic(Icons.icon(Icons.DOWNLOAD, 15));
        save.setOnAction(e -> savePng());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox actions = new HBox(10, sp, copy, save);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox right = new VBox(12, previewBox, actions);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(previewBox, Priority.ALWAYS);
        HBox body = new HBox(18, controls, right);
        body.setPadding(new Insets(4, 0, 0, 0));
        getDialogPane().setContent(body);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Runnable changed = () -> debounce.playFromStart();
        debounce.setOnFinished(e -> refresh());
        scope.valueProperty().addListener(o -> changed.run());
        scaleGroup.selectedToggleProperty().addListener(o -> changed.run());
        look.valueProperty().addListener(o -> changed.run());
        background.valueProperty().addListener(o -> { frame.setDisable(background.getValue() != Background.FRAME); changed.run(); });
        frame.valueProperty().addListener(o -> changed.run());
        grid.selectedProperty().addListener(o -> changed.run());
        padding.valueProperty().addListener(o -> changed.run());
        title.selectedProperty().addListener(o -> { titleText.setDisable(!title.isSelected()); subtitle.setDisable(!title.isSelected()); changed.run(); });
        titleText.textProperty().addListener(o -> changed.run());
        subtitle.textProperty().addListener(o -> changed.run());
        setOnShown(e -> refresh());
    }

    private Label label(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("field-label");
        return l;
    }

    private Options options() {
        double s = scaleGroup.getSelectedToggle() == null ? 2 : (double) scaleGroup.getSelectedToggle().getUserData();
        return new Options(scope.getValue(), s, look.getValue(), background.getValue(), frame.getValue(), grid.isSelected(),
                Math.round(padding.getValue()), title.isSelected(), titleText.getText(), subtitle.getText());
    }

    private void refresh() {
        Options o = options();
        double[] size = outputSize(o);
        if (size == null) {
            preview.setImage(null);
            sizeLabel.setText("Nothing to capture" + (o.scope() == Scope.SELECTION ? " — select some tables first." : "."));
            save.setDisable(true);
            copy.setDisable(true);
            return;
        }
        boolean tooBig = size[0] > MAX_SIDE || size[1] > MAX_SIDE;
        sizeLabel.setText((int) size[0] + " × " + (int) size[1] + " px" + (tooBig ? "  — too large, lower the quality" : ""));
        save.setDisable(tooBig);
        copy.setDisable(tooBig);
        // preview at a lower resolution
        double base = size[0] / o.scale();
        double ps = Math.max(0.25, Math.min(o.scale(), 1100 / Math.max(1, base)));
        Options po = new Options(o.scope(), ps, o.look(), o.background(), o.frame(), o.grid(), o.padding(), o.title(), o.titleText(), o.subtitle());
        preview.setImage(render(po, true));
    }

    /** Final image size in pixels, or null when there is nothing to capture. */
    private double[] outputSize(Options o) {
        Rectangle2D r = worldRect(o);
        if (r == null) return null;
        double s = o.scale();
        double w = r.getWidth() * s, h = r.getHeight() * s + (o.title() ? headerHeight(s) : 0);
        if (o.background() == Background.FRAME) { w += 2 * frameMargin(s); h += 2 * frameMargin(s); }
        return new double[]{Math.ceil(w), Math.ceil(h)};
    }

    private Rectangle2D worldRect(Options o) {
        if (o.scope() == Scope.VIEW) return canvas.viewportWorld();
        Bounds b = o.scope() == Scope.SELECTION ? canvas.boundsOf(canvas.selectionWithContents()) : canvas.contentBounds();
        if (b == null) return null;
        double p = o.padding();
        return new Rectangle2D(b.getMinX() - p, b.getMinY() - p, b.getWidth() + 2 * p, b.getHeight() + 2 * p);
    }

    private static double headerHeight(double s) { return 76 * s; }

    private static double frameMargin(double s) { return 72 * s; }

    /** Renders the screenshot; switches the theme temporarily if a different look was requested. */
    public WritableImage render(Options o, boolean previewMode) {
        Rectangle2D world = worldRect(o);
        if (world == null) return null;
        boolean wantDark = o.look() == Look.CURRENT ? Theme.isDark() : o.look() == Look.DARK;
        boolean switched = wantDark != Theme.isDark();
        if (switched) Theme.toggle();
        try {
            double s = o.scale();
            Set<String> only = o.scope() == Scope.SELECTION ? canvas.selectionWithContents() : null;
            WritableImage content = canvas.snapshotRegion(world, s, only);
            boolean dark = Theme.isDark();
            double w = content.getWidth(), header = o.title() ? headerHeight(s) : 0, h = content.getHeight() + header;

            Group card = new Group();
            Paint cardFill = switch (o.background()) {
                case TRANSPARENT -> Color.TRANSPARENT;
                case WHITE -> Color.WHITE;
                default -> Theme.canvasBackground();
            };
            Rectangle bg = new Rectangle(w, h, cardFill);
            card.getChildren().add(bg);
            if (o.grid() && o.background() != Background.TRANSPARENT) {
                double step = DiagramCanvas.GRID * s;
                while (step < 10) step *= 2;
                Rectangle dots = new Rectangle(0, header, w, h - header);
                double offX = -((world.getMinX() * s) % step), offY = header - ((world.getMinY() * s) % step);
                dots.setFill(new ImagePattern(dotTile(step, Math.max(0.8, 1.2 * s), o.background() == Background.WHITE
                        ? Color.web("#d7dbe2") : Theme.gridDot()), offX, offY, step, step, false));
                card.getChildren().add(dots);
            }
            ImageView iv = new ImageView(content);
            iv.setY(header);
            card.getChildren().add(iv);
            if (o.title()) {
                boolean darkText = o.background() == Background.WHITE ? false : dark;
                Color fg = darkText ? Color.web("#e6e8ee") : Color.web("#111827");
                Color muted = darkText ? Color.web("#8b93a7") : Color.web("#6b7280");
                double x = 32 * s;
                if (o.background() == Background.FRAME) {
                    String[] dotsC = {"#ff5f57", "#febc2e", "#28c840"};
                    for (int i = 0; i < 3; i++) card.getChildren().add(new Circle(x + 6 * s + i * 20 * s, 22 * s, 6 * s, Color.web(dotsC[i])));
                    x += 76 * s;
                }
                Text t = new Text(x, 34 * s, o.titleText() == null ? "" : o.titleText());
                t.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 22 * s));
                t.setFill(fg);
                Text st = new Text(x, 58 * s, o.subtitle() == null ? "" : o.subtitle());
                st.setFont(Font.font(Font.getDefault().getFamily(), 13 * s));
                st.setFill(muted);
                card.getChildren().addAll(t, st);
                if (o.background() == Background.FRAME) t.setY(30 * s);
            }

            Group root;
            if (o.background() == Background.FRAME) {
                double m = frameMargin(s), arc = 28 * s;
                Rectangle clip = new Rectangle(w, h);
                clip.setArcWidth(arc);
                clip.setArcHeight(arc);
                card.setClip(clip);
                Group shadowed = new Group(card);
                Rectangle shadowShape = new Rectangle(w, h, cardFill == Color.TRANSPARENT ? Color.WHITE : cardFill);
                shadowShape.setArcWidth(arc);
                shadowShape.setArcHeight(arc);
                shadowShape.setEffect(new DropShadow(46 * s, 0, 18 * s, Color.rgb(15, 23, 42, 0.35)));
                Group inner = new Group(shadowShape, shadowed);
                inner.setTranslateX(m);
                inner.setTranslateY(m);
                Frame f = o.frame();
                Rectangle backdrop = new Rectangle(w + 2 * m, h + 2 * m, new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web(f.a)), new Stop(0.55, Color.web(f.b)), new Stop(1, Color.web(f.c))));
                root = new Group(backdrop, inner);
            } else {
                root = new Group(card);
            }
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            double fw = o.background() == Background.FRAME ? w + 2 * frameMargin(s) : w;
            double fh = o.background() == Background.FRAME ? h + 2 * frameMargin(s) : h;
            sp.setViewport(new Rectangle2D(0, 0, Math.ceil(fw), Math.ceil(fh)));
            return root.snapshot(sp, null);
        } catch (RuntimeException | OutOfMemoryError ex) {
            if (!previewMode) status.setText("Rendering failed: " + ex.getMessage() + " — try a lower quality.");
            return null;
        } finally {
            if (switched) Theme.toggle();
        }
    }

    private static Image dotTile(double step, double r, Color color) {
        int n = (int) Math.max(2, Math.round(step));
        Canvas c = new Canvas(n, n);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(color);
        g.fillOval(n / 2.0 - r, n / 2.0 - r, 2 * r, 2 * r);
        SnapshotParameters p = new SnapshotParameters();
        p.setFill(Color.TRANSPARENT);
        return c.snapshot(p, null);
    }

    private void savePng() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save screenshot");
        fc.setInitialFileName(ExportSqlDialog.safe(diagram.name) + ".png");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        File f = fc.showSaveDialog(getDialogPane().getScene().getWindow());
        if (f == null) return;
        status.setText("Rendering…");
        WritableImage img = render(options(), false);
        if (img == null) return;
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
            status.setText("Saved " + f.getName() + "  (" + (int) img.getWidth() + " × " + (int) img.getHeight() + " px)");
        } catch (Exception ex) {
            status.setText("Could not save: " + ex.getMessage());
        }
    }
}
