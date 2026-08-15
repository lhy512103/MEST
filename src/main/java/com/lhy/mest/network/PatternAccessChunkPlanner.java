package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/** Pure partitioning policy for bounded pattern-provider payload chunks. */
final class PatternAccessChunkPlanner {
    private PatternAccessChunkPlanner() {
    }

    static <V> List<Int2ObjectMap<V>> chunk(
            Int2ObjectMap<V> source,
            int maxValues,
            int maxBytes,
            ToIntFunction<V> encodedSize,
            UnaryOperator<V> copy) {
        if (maxValues <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("chunk limits must be positive");
        }

        var result = new ArrayList<Int2ObjectMap<V>>();
        var current = new Int2ObjectArrayMap<V>();
        int currentBytes = 0;
        for (var entry : source.int2ObjectEntrySet()) {
            int valueBytes = Math.max(0, encodedSize.applyAsInt(entry.getValue()));
            if (!current.isEmpty()
                    && (current.size() >= maxValues || (long) currentBytes + valueBytes > maxBytes)) {
                result.add(current);
                current = new Int2ObjectArrayMap<>();
                currentBytes = 0;
            }
            current.put(entry.getIntKey(), copy.apply(entry.getValue()));
            currentBytes = saturatedAdd(currentBytes, valueBytes);
        }
        if (!current.isEmpty() || result.isEmpty()) {
            result.add(current);
        }
        return List.copyOf(result);
    }

    private static int saturatedAdd(int left, int right) {
        long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}