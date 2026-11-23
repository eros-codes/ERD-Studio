package com.erdstudio.layout;

import com.erdstudio.model.Diagram;
import com.erdstudio.model.Relation;
import com.erdstudio.model.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Layered (Sugiyama-style) layout.
 * <ul>
 *   <li>Referenced tables come first (left, or top), referencing tables after them.</li>
 *   <li>Crossings are reduced with barycenter sweeps.</li>
 *   <li>Layers that would get too long are wrapped into several columns (rows) so nothing becomes a tower.</li>
 *   <li>Connected groups and the isolated tables are separate blocks, packed into a widescreen-shaped picture.</li>
 * </ul>
 */
public final class AutoLayout {

    public record Size(double width, double height) {}

    public enum Style {
        LEFT_TO_RIGHT("Left → right"), TOP_TO_BOTTOM("Top → bottom"), COMPACT("Compact");

        public final String label;

        Style(String l) { label = l; }

        @Override public String toString() { return label; }
    }

    private static final double ORIGIN = 80;
    private static final double TARGET_ASPECT = 1.7;

    private AutoLayout() {}

    public static void apply(Diagram d, Set<String> subset, Function<Table, Size> size) {
        apply(d, subset, size, Style.LEFT_TO_RIGHT);
    }

    /** Arranges {@code subset} (or every table when null) and writes x/y into the tables. */
    public static void apply(Diagram d, Set<String> subset, Function<Table, Size> size, Style style) {
        List<Table> tables = new ArrayList<>();
        for (Table t : d.tables) if (subset == null || subset.contains(t.id)) tables.add(t);
        if (tables.isEmpty()) return;
        boolean compact = style == Style.COMPACT;
        boolean vertical = style == Style.TOP_TO_BOTTOM;
        double rankGap = compact ? 90 : 130, nodeGap = compact ? 30 : 46, blockGap = compact ? 80 : 120;

        double startX = ORIGIN, startY = ORIGIN;
        if (subset != null) {
            startX = Double.MAX_VALUE;
            startY = Double.MAX_VALUE;
            for (Table t : tables) { startX = Math.min(startX, t.x); startY = Math.min(startY, t.y); }
        }

        Map<String, Table> byId = new LinkedHashMap<>();
        for (Table t : tables) byId.put(t.id, t);
        Map<String, Set<String>> parents = new HashMap<>(), neighbours = new HashMap<>();
        for (Table t : tables) { parents.put(t.id, new LinkedHashSet<>()); neighbours.put(t.id, new LinkedHashSet<>()); }
        for (Relation r : d.relations) {
            if (!byId.containsKey(r.fromTableId) || !byId.containsKey(r.toTableId)) continue;
            if (r.fromTableId.equals(r.toTableId)) continue;
            parents.get(r.fromTableId).add(r.toTableId);
            neighbours.get(r.fromTableId).add(r.toTableId);
            neighbours.get(r.toTableId).add(r.fromTableId);
        }

        // connected components
        List<List<Table>> components = new ArrayList<>();
        List<Table> isolated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Table t : tables) {
            if (seen.contains(t.id)) continue;
            List<Table> comp = new ArrayList<>();
            List<String> stack = new ArrayList<>(List.of(t.id));
            seen.add(t.id);
            while (!stack.isEmpty()) {
                String id = stack.remove(stack.size() - 1);
                comp.add(byId.get(id));
                for (String n : neighbours.get(id)) if (seen.add(n)) stack.add(n);
            }
            if (comp.size() == 1) isolated.add(comp.get(0));
            else components.add(comp);
        }

        // each component / the isolated tables become a block laid out from (0,0)
        List<Block> blocks = new ArrayList<>();
        for (List<Table> comp : components) {
            blocks.add(layoutComponent(comp, parents, neighbours, size, vertical, rankGap, nodeGap));
        }
        if (!isolated.isEmpty()) blocks.add(layoutGrid(isolated, size, nodeGap + 14, nodeGap));
        blocks.sort(Comparator.comparingDouble((Block b) -> b.w * b.h).reversed());
        pack(blocks, startX, startY, blockGap);
    }

    // ------------------------------------------------------------------ blocks

    private static final class Block {
        final List<Table> tables = new ArrayList<>();
        final Map<String, double[]> local = new HashMap<>();
        double w, h;
    }

    /** Places blocks in rows; the row width is chosen so the whole picture is closest to TARGET_ASPECT. */
    private static void pack(List<Block> blocks, double ox, double oy, double gap) {
        double bestLimit = Double.MAX_VALUE, bestScore = Double.MAX_VALUE, acc = 0;
        for (int k = 0; k < blocks.size(); k++) {
            acc += blocks.get(k).w + (k > 0 ? gap : 0);
            double limit = acc + 1, cx = 0, cy = 0, rh = 0, maxW = 0;
            for (Block b : blocks) {
                if (cx > 0 && cx + b.w > limit) { cy += rh + gap; cx = 0; rh = 0; }
                cx += b.w + gap;
                rh = Math.max(rh, b.h);
                maxW = Math.max(maxW, cx - gap);
            }
            double score = Math.abs(Math.log((maxW / Math.max(1, cy + rh)) / TARGET_ASPECT));
            if (score < bestScore - 1e-9) { bestScore = score; bestLimit = limit; }
        }
        double x = 0, y = 0, rowH = 0;
        for (Block b : blocks) {
            if (x > 0 && x + b.w > bestLimit) { x = 0; y += rowH + gap; rowH = 0; }
            for (Table t : b.tables) {
                double[] p = b.local.get(t.id);
                t.x = Math.round(ox + x + p[0]);
                t.y = Math.round(oy + y + p[1]);
            }
            x += b.w + gap;
            rowH = Math.max(rowH, b.h);
        }
    }

    /** Tables without relations: a grid whose shape is close to TARGET_ASPECT. */
    private static Block layoutGrid(List<Table> list, Function<Table, Size> size, double hGap, double vGap) {
        List<Table> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(t -> t.name.toLowerCase()));
        double area = 0, maxW = 0;
        for (Table t : sorted) {
            Size s = size.apply(t);
            area += (s.width() + hGap) * (s.height() + vGap);
            maxW = Math.max(maxW, s.width());
        }
        double rowLimit = Math.max(maxW, Math.sqrt(area * TARGET_ASPECT));
        Block b = new Block();
        double x = 0, y = 0, rowH = 0;
        for (Table t : sorted) {
            Size s = size.apply(t);
            if (x > 0 && x + s.width() > rowLimit) { x = 0; y += rowH + vGap; rowH = 0; }
            b.tables.add(t);
            b.local.put(t.id, new double[]{x, y});
            x += s.width() + hGap;
            rowH = Math.max(rowH, s.height());
            b.w = Math.max(b.w, x - hGap);
        }
        b.h = y + rowH;
        return b;
    }

    private static Block layoutComponent(List<Table> comp, Map<String, Set<String>> parents, Map<String, Set<String>> neighbours,
                                         Function<Table, Size> size, boolean vertical, double rankGap, double nodeGap) {
        Map<String, Integer> rank = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        for (Table t : comp) rankOf(t.id, parents, rank, visiting);
        int maxRank = 0;
        for (int r : rank.values()) maxRank = Math.max(maxRank, r);
        List<List<Table>> layers = new ArrayList<>();
        for (int i = 0; i <= maxRank; i++) layers.add(new ArrayList<>());
        for (Table t : comp) layers.get(rank.get(t.id)).add(t);

        // crossing reduction: alternate down/up barycenter sweeps
        Map<String, Double> pos = new HashMap<>();
        for (List<Table> layer : layers) for (int i = 0; i < layer.size(); i++) pos.put(layer.get(i).id, (double) i);
        for (int iter = 0; iter < 10; iter++) {
            boolean down = iter % 2 == 0;
            for (int li = down ? 1 : maxRank - 1; down ? li <= maxRank : li >= 0; li += down ? 1 : -1) {
                int ref = down ? li - 1 : li + 1;
                List<Table> layer = layers.get(li);
                Map<String, Double> bary = new HashMap<>();
                for (Table t : layer) {
                    double sum = 0;
                    int n = 0;
                    for (String nb : neighbours.get(t.id)) {
                        Integer r = rank.get(nb);
                        if (r != null && r == ref) { sum += pos.get(nb); n++; }
                    }
                    bary.put(t.id, n == 0 ? pos.get(t.id) : sum / n);
                }
                layer.sort(Comparator.comparingDouble(t -> bary.get(t.id)));
                for (int i = 0; i < layer.size(); i++) pos.put(layer.get(i).id, (double) i);
            }
        }

        // limit how long a layer may get, then wrap it into several columns (or rows)
        double total = 0;
        for (Table t : comp) {
            Size s = size.apply(t);
            total += (s.width() + rankGap) * (s.height() + nodeGap);
        }
        double maxAlong = vertical
                ? Math.max(1100, Math.sqrt(total * TARGET_ASPECT) * 1.15)
                : Math.max(760, Math.sqrt(total / TARGET_ASPECT) * 1.25);

        Block b = new Block();
        double cross = 0;            // running position across layers (x for LR, y for TB)
        List<List<List<Table>>> wrapped = new ArrayList<>();
        for (List<Table> layer : layers) {
            // split into sub-columns of at most maxAlong
            List<List<Table>> cols = new ArrayList<>();
            List<Table> cur = new ArrayList<>();
            double run = 0;
            for (Table t : layer) {
                Size s = size.apply(t);
                double len = vertical ? s.width() : s.height();
                if (!cur.isEmpty() && run + len > maxAlong) { cols.add(cur); cur = new ArrayList<>(); run = 0; }
                cur.add(t);
                run += len + nodeGap;
            }
            if (!cur.isEmpty()) cols.add(cur);
            wrapped.add(cols);
        }
        double tallest = 0;
        for (List<List<Table>> cols : wrapped) {
            for (List<Table> col : cols) {
                double len = 0;
                for (Table t : col) { Size s = size.apply(t); len += (vertical ? s.width() : s.height()) + nodeGap; }
                tallest = Math.max(tallest, len - nodeGap);
            }
        }
        for (List<List<Table>> cols : wrapped) {
            for (int ci = 0; ci < cols.size(); ci++) {
                List<Table> col = cols.get(ci);
                double thick = 0, len = 0;
                for (Table t : col) {
                    Size s = size.apply(t);
                    thick = Math.max(thick, vertical ? s.height() : s.width());
                    len += (vertical ? s.width() : s.height()) + nodeGap;
                }
                len -= nodeGap;
                double along = (tallest - len) / 2; // centre each column
                for (Table t : col) {
                    Size s = size.apply(t);
                    double x = vertical ? along : cross, y = vertical ? cross : along;
                    b.tables.add(t);
                    b.local.put(t.id, new double[]{x, y});
                    along += (vertical ? s.width() : s.height()) + nodeGap;
                    b.w = Math.max(b.w, x + s.width());
                    b.h = Math.max(b.h, y + s.height());
                }
                cross += thick + (ci < cols.size() - 1 ? nodeGap + 24 : rankGap);
            }
        }
        return b;
    }

    private static int rankOf(String id, Map<String, Set<String>> parents, Map<String, Integer> rank, Set<String> visiting) {
        Integer known = rank.get(id);
        if (known != null) return known;
        if (!visiting.add(id)) return 0; // cycle: break it here
        int r = 0;
        for (String p : parents.get(id)) r = Math.max(r, rankOf(p, parents, rank, visiting) + 1);
        visiting.remove(id);
        rank.put(id, r);
        return r;
    }
}
