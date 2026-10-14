package com.aibuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A build as a map of relative position -> block state string, produced by running the AI's
 * "ops" program. x/z are relative to the center column, y = 0 is the bottom layer.
 * Pure Java (no Minecraft classes) so it is easy to test.
 */
public final class BuildPlan {
    public record P(int x, int y, int z) {}

    public final Map<P, String> blocks = new LinkedHashMap<>();
    public JsonObject source;
    public String name = "build";
    public int minX, minY, minZ, maxX, maxY, maxZ;

    private final Map<String, String> palette = new HashMap<>();
    private int maxBlocks;
    private boolean mirX, mirZ;
    /** Number of separate (not touching) pieces; 1 = everything is connected. */
    public int pieces = 1;

    private BuildPlan() {}

    public String sizeText() {
        return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1)
                + " (W x H x D)";
    }

    public static BuildPlan fromJson(JsonObject root, int maxBlocks) {
        BuildPlan bp = new BuildPlan();
        bp.source = root;
        bp.maxBlocks = maxBlocks;
        if (root.has("name") && root.get("name").isJsonPrimitive()) bp.name = root.get("name").getAsString();
        if (root.has("palette") && root.get("palette").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("palette").entrySet()) {
                bp.palette.put(e.getKey(), e.getValue().getAsString());
            }
        }
        if (root.has("mirror") && root.get("mirror").isJsonPrimitive()) {
            String m = root.get("mirror").getAsString().toLowerCase();
            bp.mirX = m.contains("x");
            bp.mirZ = m.contains("z");
        }
        JsonArray ops = root.has("ops") && root.get("ops").isJsonArray() ? root.getAsJsonArray("ops") : null;
        if (ops == null) throw new IllegalArgumentException("missing \"ops\" array");
        int i = 0;
        for (JsonElement el : ops) {
            i++;
            try {
                bp.apply(el.getAsJsonObject());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("op #" + i + ": " + e.getMessage(), e);
            }
        }
        if (bp.blocks.isEmpty()) throw new IllegalArgumentException("the plan places no blocks");

        boolean first = true;
        for (P p : bp.blocks.keySet()) {
            if (first) {
                bp.minX = bp.maxX = p.x(); bp.minY = bp.maxY = p.y(); bp.minZ = bp.maxZ = p.z();
                first = false;
            } else {
                bp.minX = Math.min(bp.minX, p.x()); bp.maxX = Math.max(bp.maxX, p.x());
                bp.minY = Math.min(bp.minY, p.y()); bp.maxY = Math.max(bp.maxY, p.y());
                bp.minZ = Math.min(bp.minZ, p.z()); bp.maxZ = Math.max(bp.maxZ, p.z());
            }
        }
        return bp;
    }

    // ------------------------------------------------------------------ ops

    private void apply(JsonObject o) {
        String op = str(o, "op").toLowerCase();
        switch (op) {
            case "block" -> {
                int[] p = pos(o, "pos");
                set(p[0], p[1], p[2], block(o));
            }
            case "blocks" -> {
                String b = block(o);
                JsonArray list = o.getAsJsonArray("list");
                if (list == null) throw new IllegalArgumentException("\"blocks\" needs a \"list\"");
                for (JsonElement e : list) {
                    JsonArray a = e.getAsJsonArray();
                    set(round(a.get(0)), round(a.get(1)), round(a.get(2)), b);
                }
            }
            case "box", "hollow", "walls" -> {
                int[] a = pos(o, "from"), b = pos(o, "to");
                int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
                int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
                int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
                if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 8_000_000L)
                    throw new IllegalArgumentException("box is far too large");
                int t = Math.max(1, num(o, "t", 1));
                String blk = block(o);
                for (int x = x0; x <= x1; x++)
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++) {
                            boolean side = x - x0 < t || x1 - x < t || z - z0 < t || z1 - z < t;
                            boolean vert = y - y0 < t || y1 - y < t;
                            boolean put = switch (op) {
                                case "box" -> true;
                                case "walls" -> side;
                                default -> side || vert;
                            };
                            if (put) set(x, y, z, blk);
                        }
            }
            case "line" -> {
                int[] a = pos(o, "from"), b = pos(o, "to");
                String blk = block(o);
                int dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
                int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                if (steps > 4096) throw new IllegalArgumentException("line too long");
                if (steps == 0) { set(a[0], a[1], a[2], blk); break; }
                for (int i = 0; i <= steps; i++) {
                    double f = (double) i / steps;
                    set((int) Math.round(a[0] + dx * f), (int) Math.round(a[1] + dy * f),
                            (int) Math.round(a[2] + dz * f), blk);
                }
            }
            case "cylinder" -> {
                int[] c = pos(o, "center");
                int r = Math.max(0, num(o, "radius", 1));
                int h = Math.max(1, num(o, "height", 1));
                if (r > 256 || h > 1024) throw new IllegalArgumentException("cylinder too large");
                String axis = o.has("axis") ? o.get("axis").getAsString().toLowerCase() : "y";
                boolean hollow = bool(o, "hollow");
                String blk = block(o);
                double outer = (r + 0.5) * (r + 0.5), inner = (r - 0.5) * (r - 0.5);
                for (int a = -r; a <= r; a++)
                    for (int b = -r; b <= r; b++) {
                        double d2 = a * a + b * b;
                        if (d2 > outer) continue;
                        if (hollow && r >= 1 && d2 <= inner) continue;
                        for (int t = 0; t < h; t++) {
                            switch (axis) {
                                case "x" -> set(c[0] + t, c[1] + a, c[2] + b, blk);
                                case "z" -> set(c[0] + a, c[1] + b, c[2] + t, blk);
                                default -> set(c[0] + a, c[1] + t, c[2] + b, blk);
                            }
                        }
                    }
            }
            case "ellipsoid" -> {
                int[] c = pos(o, "center");
                JsonArray rr = o.has("radii") && o.get("radii").isJsonArray() ? o.getAsJsonArray("radii") : null;
                if (rr == null || rr.size() != 3) throw new IllegalArgumentException("\"radii\" must be [rx,ry,rz]");
                double rx = Math.max(0.5, rr.get(0).getAsDouble());
                double ry = Math.max(0.5, rr.get(1).getAsDouble());
                double rz = Math.max(0.5, rr.get(2).getAsDouble());
                int ix = (int) Math.ceil(rx), iy = (int) Math.ceil(ry), iz = (int) Math.ceil(rz);
                if (ix > 256 || iy > 256 || iz > 256) throw new IllegalArgumentException("ellipsoid too large");
                boolean hollow = bool(o, "hollow") && rx > 1 && ry > 1 && rz > 1;
                String blk = block(o);
                double ox = (rx + 0.5) * (rx + 0.5), oy = (ry + 0.5) * (ry + 0.5), oz = (rz + 0.5) * (rz + 0.5);
                double jx = (rx - 0.5) * (rx - 0.5), jy = (ry - 0.5) * (ry - 0.5), jz = (rz - 0.5) * (rz - 0.5);
                for (int dx = -ix; dx <= ix; dx++)
                    for (int dy = -iy; dy <= iy; dy++)
                        for (int dz = -iz; dz <= iz; dz++) {
                            double v = dx * dx / ox + dy * dy / oy + dz * dz / oz;
                            if (v > 1) continue;
                            if (hollow && (dx * dx / jx + dy * dy / jy + dz * dz / jz) <= 1) continue;
                            set(c[0] + dx, c[1] + dy, c[2] + dz, blk);
                        }
            }
            case "sphere" -> {
                int[] c = pos(o, "center");
                int r = Math.max(0, num(o, "radius", 1));
                if (r > 256) throw new IllegalArgumentException("sphere too large");
                boolean hollow = bool(o, "hollow");
                String half = o.has("half") ? o.get("half").getAsString().toLowerCase() : "full";
                String blk = block(o);
                double outer = (r + 0.5) * (r + 0.5), inner = (r - 0.5) * (r - 0.5);
                for (int dx = -r; dx <= r; dx++)
                    for (int dy = -r; dy <= r; dy++)
                        for (int dz = -r; dz <= r; dz++) {
                            if (half.equals("top") && dy < 0) continue;
                            if (half.equals("bottom") && dy > 0) continue;
                            double d2 = dx * dx + dy * dy + dz * dz;
                            if (d2 > outer) continue;
                            if (hollow && r >= 1 && d2 <= inner) continue;
                            set(c[0] + dx, c[1] + dy, c[2] + dz, blk);
                        }
            }
            case "pyramid" -> {
                int[] c = pos(o, "center");
                int size = Math.max(0, num(o, "size", 1));
                boolean hollow = bool(o, "hollow");
                String blk = block(o);
                for (int layer = 0; layer <= size; layer++) {
                    int half = size - layer;
                    for (int dx = -half; dx <= half; dx++)
                        for (int dz = -half; dz <= half; dz++) {
                            boolean ring = Math.abs(dx) == half || Math.abs(dz) == half;
                            if (hollow && !ring && layer != size) continue;
                            set(c[0] + dx, c[1] + layer, c[2] + dz, blk);
                        }
                }
            }
            default -> throw new IllegalArgumentException("unknown op \"" + op + "\"");
        }
    }

    // -------------------------------------------------------------- helpers

    private void set(int x, int y, int z, String id) {
        if (id == null) return; // "keep"
        put(x, y, z, id);
        if (mirX) put(-x, y, z, id);
        if (mirZ) {
            put(x, y, -z, id);
            if (mirX) put(-x, y, -z, id);
        }
    }

    private void put(int x, int y, int z, String id) {
        blocks.put(new P(x, y, z), id);
        if (blocks.size() > maxBlocks)
            throw new IllegalArgumentException("build is bigger than the limit of " + maxBlocks + " blocks");
    }

    /** Counts separate pieces (26-neighbour connectivity, air ignored). */
    public void analyzeConnectivity() {
        Set<P> solid = new HashSet<>();
        for (Map.Entry<P, String> e : blocks.entrySet()) {
            if (!e.getValue().equals("minecraft:air")) solid.add(e.getKey());
        }
        Set<P> seen = new HashSet<>();
        ArrayDeque<P> q = new ArrayDeque<>();
        int comps = 0;
        for (P s0 : solid) {
            if (seen.contains(s0)) continue;
            comps++;
            seen.add(s0);
            q.add(s0);
            while (!q.isEmpty()) {
                P p = q.poll();
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            P n = new P(p.x() + dx, p.y() + dy, p.z() + dz);
                            if (solid.contains(n) && seen.add(n)) q.add(n);
                        }
            }
        }
        pieces = Math.max(1, comps);
    }

    /** Looks up the "block" field in the palette and normalises it. null means "keep". */
    private String block(JsonObject o) {
        if (!o.has("block")) throw new IllegalArgumentException("missing \"block\"");
        String key = o.get("block").getAsString();
        String v = palette.getOrDefault(key, key).trim().toLowerCase();
        if (v.equals("keep")) return null;
        if (v.equals("air")) return "minecraft:air";
        int br = v.indexOf('[');
        String base = br < 0 ? v : v.substring(0, br);
        if (!base.contains(":")) v = "minecraft:" + v;
        return v;
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k)) throw new IllegalArgumentException("missing \"" + k + "\"");
        return o.get(k).getAsString();
    }

    private static int[] pos(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray() || o.getAsJsonArray(k).size() != 3)
            throw new IllegalArgumentException("\"" + k + "\" must be [x,y,z]");
        JsonArray a = o.getAsJsonArray(k);
        return new int[]{round(a.get(0)), round(a.get(1)), round(a.get(2))};
    }

    private static int round(JsonElement e) {
        return (int) Math.round(e.getAsDouble());
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) ? round(o.get(k)) : def;
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).getAsBoolean();
    }
}
