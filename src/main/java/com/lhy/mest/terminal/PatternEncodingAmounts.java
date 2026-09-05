package com.lhy.mest.terminal;

import java.util.ArrayList;
import java.util.List;

import appeng.api.stacks.GenericStack;

public final class PatternEncodingAmounts {
    private PatternEncodingAmounts() {
    }

    public static boolean canScaleAmount(long amount, int scale, boolean divide) {
        if (scale <= 0 || amount < 0) {
            return false;
        }
        return divide ? amount % scale == 0 : amount <= Long.MAX_VALUE / scale;
    }

    public static long scaledAmount(long amount, int scale, boolean divide) {
        if (!canScaleAmount(amount, scale, divide)) {
            throw new IllegalArgumentException("Invalid encoding scale");
        }
        return divide ? amount / scale : amount * scale;
    }

    public static List<GenericStack> divideStacks(List<GenericStack> stacks, long divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("Invalid encoding divisor");
        }
        var result = new ArrayList<GenericStack>(stacks.size());
        for (GenericStack stack : stacks) {
            result.add(stack == null ? null : new GenericStack(stack.what(), stack.amount() / divisor));
        }
        return result;
    }
    public static long gcd(long left, long right) {
        if (left == Long.MIN_VALUE || right == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Amount out of range");
        }
        left = Math.abs(left);
        right = Math.abs(right);
        while (right != 0L) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }
}
