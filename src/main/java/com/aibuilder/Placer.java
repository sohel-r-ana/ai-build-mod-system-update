package com.aibuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Minecraft-side block placement. */
final class Placer {
    private Placer() {}

    record E(BlockPos pos, BlockState state) {}

    /** Parses "minecraft:oak_stairs[facing=north,half=bottom]". Returns null if the block does not exist. */
    static BlockState parseState(String s) {
        int br = s.indexOf('[');
        String id = br < 0 ? s : s.substring(0, br);
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return null;
        Optional<Block> ob = BuiltInRegistries.BLOCK.getOptional(rl);
        if (ob.isEmpty()) return null;
        BlockState st = ob.get().defaultBlockState();
        if (br >= 0 && s.endsWith("]")) {
            for (String kv : s.substring(br + 1, s.length() - 1).split(",")) {
                String[] a = kv.split("=", 2);
                if (a.length < 2) continue;
                Property<?> p = st.getBlock().getStateDefinition().getProperty(a[0].trim());
                if (p == null) continue;
                st = withValue(st, p, a[1].trim());
            }
        }
        return st;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState st, Property<T> p, String v) {
        return p.getValue(v).map(x -> st.setValue(p, x)).orElse(st);
    }

    /** Converts the plan into world positions + states, solids first, bottom to top. */
    static List<E> prepare(BuildPlan plan, BlockPos origin, ServerLevel level, Set<String> unknown) {
        Map<String, BlockState> cache = new HashMap<>();
        List<E> out = new ArrayList<>(plan.blocks.size());
        for (Map.Entry<BuildPlan.P, String> e : plan.blocks.entrySet()) {
            String id = e.getValue();
            BlockState st;
            if (cache.containsKey(id)) {
                st = cache.get(id);
            } else {
                st = parseState(id);
                cache.put(id, st);
                if (st == null) unknown.add(id);
            }
            if (st == null) continue;
            BuildPlan.P p = e.getKey();
            BlockPos pos = origin.offset(p.x(), p.y(), p.z());
            if (level.isOutsideBuildHeight(pos)) continue;
            out.add(new E(pos, st));
        }
        out.sort(Comparator.<E>comparingInt(x -> x.pos().getY())
                .thenComparingInt(x -> x.state().canOcclude() ? 0 : 1));
        return out;
    }

    /** Places blocks a few thousand per tick so the server does not freeze. */
    static final class Job {
        private final ServerLevel level;
        private final List<E> list;
        private final Map<BlockPos, BlockState> undo; // null = do not record
        private final Runnable onDone;
        private int idx;

        Job(ServerLevel level, List<E> list, Map<BlockPos, BlockState> undo, Runnable onDone) {
            this.level = level;
            this.list = list;
            this.undo = undo;
            this.onDone = onDone;
        }

        int total() { return list.size(); }

        /** @return true when finished */
        boolean step(int budget) {
            int n = 0;
            while (idx < list.size() && n < budget) {
                E e = list.get(idx++);
                BlockState old = level.getBlockState(e.pos());
                if (undo != null) undo.putIfAbsent(e.pos(), old);
                if (old != e.state()) level.setBlock(e.pos(), e.state(), Block.UPDATE_ALL);
                n++;
            }
            if (idx >= list.size()) {
                onDone.run();
                return true;
            }
            return false;
        }
    }
}
