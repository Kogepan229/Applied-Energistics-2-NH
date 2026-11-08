package appeng.api.stacks;

import java.util.Comparator;

import appeng.api.config.FuzzyMode;
import it.unimi.dsi.fastutil.objects.Object2LongSortedMap;

/**
 * Damage-ordered maps for keys that support fuzzy range search, and the rules of their fuzzy partitions.
 */
final class FuzzySearch {

    private static final KeyComparator COMPARATOR = new KeyComparator();

    private FuzzySearch() {}

    static AEKey2LongMap.AVLTreeMap createMap2Long() {
        return new AEKey2LongMap.AVLTreeMap(COMPARATOR);
    }

    /**
     * @return a live view of the entries in {@code map} that are in the same fuzzy partition as {@code key}, most
     *         damaged first.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static Object2LongSortedMap<AEKey> findFuzzy(Object2LongSortedMap<AEKey> map, AEKey key, FuzzyMode fuzzy) {
        int lowerBound = getLowerBound(key, fuzzy);
        int upperBound = getUpperBound(key, fuzzy);

        // Keys are sorted by descending value, and a FuzzyBound sorts before every key with the same value. So the
        // bound at the upper value starts the range, and the bound just below the lower value ends it.
        return (Object2LongSortedMap<AEKey>) ((Object2LongSortedMap) map)
                .subMap(new FuzzyBound(upperBound), new FuzzyBound(lowerBound - 1));
    }

    /**
     * @return whether {@link #findFuzzy} with {@code filter} would include {@code candidate}.
     */
    static boolean isInRange(AEKey filter, FuzzyMode fuzzy, AEKey candidate) {
        int value = candidate.getFuzzySearchValue();
        return value >= getLowerBound(filter, fuzzy) && value <= getUpperBound(filter, fuzzy);
    }

    /**
     * Inclusive lower bound of the fuzzy partition containing {@code key}. PERCENT_99 looks at the search value, and
     * the percentage modes decide the partition by the partition value.
     */
    private static int getLowerBound(AEKey key, FuzzyMode fuzzy) {
        int maxValue = requireMaxValue(key);
        return switch (fuzzy) {
            case IGNORE_ALL -> 0;
            case PERCENT_99 -> key.getFuzzySearchValue() == 0 ? 0 : 1;
            default -> {
                int breakpoint = fuzzy.calculateBreakPoint(maxValue);
                yield breakpoint <= key.getFuzzyPartitionValue() ? breakpoint : 0;
            }
        };
    }

    /**
     * Inclusive upper bound of the fuzzy partition containing {@code key}. Values above the max value (negative
     * durability) belong to the most damaged partition.
     */
    private static int getUpperBound(AEKey key, FuzzyMode fuzzy) {
        int maxValue = requireMaxValue(key);
        return switch (fuzzy) {
            case IGNORE_ALL -> maxValue + 1;
            case PERCENT_99 -> key.getFuzzySearchValue() == 0 ? 0 : maxValue + 1;
            default -> {
                int breakpoint = fuzzy.calculateBreakPoint(maxValue);
                yield key.getFuzzyPartitionValue() < breakpoint ? breakpoint - 1 : maxValue + 1;
            }
        };
    }

    private static int requireMaxValue(AEKey key) {
        int maxValue = key.getFuzzySearchMaxValue();
        if (maxValue <= 0) {
            throw new IllegalArgumentException(
                    "Cannot use fuzzy range search on a key without a fuzzy max value: " + key);
        }
        return maxValue;
    }

    private static final class FuzzyBound {

        private final int itemDamage;

        private FuzzyBound(int itemDamage) {
            this.itemDamage = itemDamage;
        }
    }

    /**
     * Orders keys of the same primary item by descending value, so that the most damaged come first. Ties are broken by
     * hash code and identity so that distinct keys never compare as equal.
     */
    private static final class KeyComparator implements Comparator<Object> {

        @Override
        public int compare(Object a, Object b) {
            FuzzyBound boundA = a instanceof FuzzyBound bound ? bound : null;
            FuzzyBound boundB = b instanceof FuzzyBound bound ? bound : null;

            int damageA = boundA != null ? boundA.itemDamage : ((AEKey) a).getFuzzySearchValue();
            int damageB = boundB != null ? boundB.itemDamage : ((AEKey) b).getFuzzySearchValue();

            int damageCompare = Integer.compare(damageB, damageA);
            if (damageCompare != 0) {
                return damageCompare;
            }
            if (boundA != null || boundB != null) {
                // A bound sorts before the keys with the same value.
                return boundA != null ? (boundB != null ? 0 : -1) : 1;
            }

            AEKey keyA = (AEKey) a;
            AEKey keyB = (AEKey) b;
            if (keyA.equals(keyB)) {
                return 0;
            }

            int hashCompare = Integer.compare(keyA.hashCode(), keyB.hashCode());
            if (hashCompare != 0) {
                return hashCompare;
            }

            return Integer.compare(System.identityHashCode(keyA), System.identityHashCode(keyB));
        }
    }
}
