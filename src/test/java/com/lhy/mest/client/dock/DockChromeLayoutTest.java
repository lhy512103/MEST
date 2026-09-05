package com.lhy.mest.client.dock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

class DockChromeLayoutTest {
    @Test
    void optimizedPredicateMatchesPairwiseOracle() {
        Random random = new Random(0x4d455354L);
        for (int count = 0; count <= 32; count++) {
            for (int iteration = 0; iteration < 500; iteration++) {
                int[] x = new int[count];
                int[] width = new int[count];
                int max = Integer.MIN_VALUE;
                int second = Integer.MIN_VALUE;
                int maxCount = 0;
                for (int index = 0; index < count; index++) {
                    x[index] = random.nextInt(11) - 5;
                    width[index] = random.nextInt(4);
                    if (x[index] > max) {
                        second = max;
                        max = x[index];
                        maxCount = 1;
                    } else if (x[index] == max) {
                        maxCount++;
                    } else if (x[index] > second) {
                        second = x[index];
                    }
                }
                for (int index = 0; index < count; index++) {
                    boolean expected = true;
                    for (int other = 0; other < count; other++) {
                        if (other != index && x[other] >= x[index] + width[index] - 1) {
                            expected = false;
                            break;
                        }
                    }
                    assertEquals(expected, DockChromeLayout.isRightmost(
                            x[index], width[index], max, second, maxCount));
                }
            }
        }
    }
}
