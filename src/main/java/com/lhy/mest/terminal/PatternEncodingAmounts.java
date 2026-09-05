package com.lhy.mest.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;

public final class PatternEncodingAmounts {
    private PatternEncodingAmounts() {
    }

    public static List<GenericStack> copyInv(ConfigInventory inventory) {
        var result = new ArrayList<GenericStack>(inventory.size());
        for (int i = 0; i < inventory.size(); i++) {
            result.add(inventory.getStack(i));
        }
        return result;
    }

    public static GenericStack[] copyInvArray(ConfigInventory inventory) {
        return copyInv(inventory).toArray(GenericStack[]::new);
    }

    public static boolean canScale(GenericStack[] stacks, int scale, boolean divide) {
        for (GenericStack stack : stacks) {
            if (stack == null) {
                continue;
            }
            if (divide) {
                if (stack.amount() % scale != 0) {
                    return false;
                }
            } else if (stack.amount() * (long) scale > 999999L * stack.what().getAmountPerUnit()) {
                return false;
            }
        }
        return true;
    }

    public static GenericStack[] scaleStacks(GenericStack[] source, int scale, boolean divide) {
        var result = new GenericStack[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] != null) {
                long amount = scaledAmount(source[i].amount(), scale, divide);
                result[i] = new GenericStack(source[i].what(), amount);
            }
        }
        return result;
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
    public static long sharedGcd(List<GenericStack> inputs, List<GenericStack> outputs) {
        long gcd = 0L;
        gcd = updateGcd(gcd, inputs);
        gcd = updateGcd(gcd, outputs);
        return gcd;
    }

    private static long updateGcd(long current, List<GenericStack> stacks) {
        long gcd = current;
        for (GenericStack stack : stacks) {
            if (stack == null || stack.amount() <= 0L) {
                continue;
            }
            gcd = gcd == 0L ? stack.amount() : gcd(gcd, stack.amount());
            if (gcd == 1L) {
                return 1L;
            }
        }
        return gcd;
    }

    public static GenericStack[] rotateOutputs(GenericStack[] outputs) {
        int filled = 0;
        for (GenericStack output : outputs) {
            if (output != null) {
                filled++;
            }
        }
        if (filled < 2) {
            return outputs;
        }
        var rotated = Arrays.copyOf(outputs, outputs.length);
        for (int i = 0; i < outputs.length; i++) {
            if (outputs[i] == null) {
                continue;
            }
            for (int offset = 1; offset < outputs.length; offset++) {
                GenericStack next = outputs[(i + offset) % outputs.length];
                if (next != null) {
                    rotated[i] = new GenericStack(next.what(), next.amount());
                    break;
                }
            }
        }
        return rotated;
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
