package appeng.api.stacks;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import org.jspecify.annotations.Nullable;

import appeng.api.config.FuzzyMode;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;

/**
 * Tallies a negative or positive amount for sub-variants of an {@link AEKey}.
 */
abstract class VariantCounter implements Iterable<Object2LongMap.Entry<AEKey>> {

    public abstract long get(AEKey key);

    public abstract void add(AEKey key, long amount);

    public abstract void set(AEKey key, long amount);

    public abstract long remove(AEKey key);

    public void addAll(VariantCounter other) {
        for (Object2LongMap.Entry<AEKey> entry : other) {
            add(entry.getKey(), entry.getLongValue());
        }
    }

    public void removeAll(VariantCounter other) {
        for (Object2LongMap.Entry<AEKey> entry : other) {
            add(entry.getKey(), -entry.getLongValue());
        }
    }

    public void forEachEntry(KeyCounter.EntryConsumer consumer) {
        for (Object2LongMap.Entry<AEKey> entry : this) {
            consumer.accept(entry.getKey(), entry.getLongValue());
        }
    }

    public abstract Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEKey filter, FuzzyMode fuzzy);

    public abstract int size();

    public boolean isEmpty() {
        return size() == 0;
    }

    /** A live view with distinct entries, suitable for collecting fuzzy results. */
    abstract Collection<Object2LongMap.Entry<AEKey>> entries();

    public void reset() {
        for (Object2LongMap.Entry<AEKey> entry : this) {
            entry.setValue(0L);
        }
    }

    public abstract void clear();

    public abstract VariantCounter copy();

    public void invert() {
        for (Object2LongMap.Entry<AEKey> entry : this) {
            entry.setValue(-entry.getLongValue());
        }
    }

    public void removeZeros() {
        Iterator<Object2LongMap.Entry<AEKey>> iterator = iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getLongValue() == 0L) {
                iterator.remove();
            }
        }
    }

    static final class UnorderedVariantMap extends VariantCounter {

        private @Nullable AEKey singleKey;
        private long singleAmount;
        private AEKey2LongMap.@Nullable OpenHashMap records;
        private @Nullable Collection<Object2LongMap.Entry<AEKey>> entries;

        @Override
        public long get(AEKey key) {
            if (this.records != null) {
                return this.records.getLong(key);
            }
            return key.equals(this.singleKey) ? this.singleAmount : 0L;
        }

        @Override
        public void add(AEKey key, long amount) {
            if (this.records != null) {
                this.records.addTo(key, amount);
            } else if (this.singleKey == null) {
                this.singleKey = key;
                this.singleAmount = amount;
            } else if (key.equals(this.singleKey)) {
                this.singleAmount += amount;
            } else {
                promote().put(key, amount);
            }
        }

        @Override
        public void set(AEKey key, long amount) {
            if (this.records != null) {
                this.records.put(key, amount);
            } else if (this.singleKey == null || key.equals(this.singleKey)) {
                if (this.singleKey == null) {
                    this.singleKey = key;
                }
                this.singleAmount = amount;
            } else {
                promote().put(key, amount);
            }
        }

        private AEKey2LongMap.OpenHashMap promote() {
            // The second distinct key triggers allocation. Most buckets never need a map.
            this.records = new AEKey2LongMap.OpenHashMap(2);
            this.records.put(this.singleKey, this.singleAmount);
            this.singleKey = null;
            this.singleAmount = 0L;
            return this.records;
        }

        @Override
        public long remove(AEKey key) {
            if (this.records != null) {
                return this.records.removeLong(key);
            }
            if (!key.equals(this.singleKey)) {
                return 0L;
            }
            long amount = this.singleAmount;
            this.singleKey = null;
            this.singleAmount = 0L;
            return amount;
        }

        @Override
        public int size() {
            return this.records != null ? this.records.size() : this.singleKey == null ? 0 : 1;
        }

        @Override
        public void clear() {
            if (this.records != null) {
                // Keep promoted maps for reuse, just like the enclosing KeyCounter keeps its buckets.
                this.records.clear();
            } else {
                this.singleKey = null;
                this.singleAmount = 0L;
            }
        }

        @Override
        public void reset() {
            if (this.records != null) {
                super.reset();
            } else {
                this.singleAmount = 0L;
            }
        }

        @Override
        public void invert() {
            if (this.records != null) {
                super.invert();
            } else {
                this.singleAmount = -this.singleAmount;
            }
        }

        @Override
        public void removeZeros() {
            if (this.records != null) {
                this.records.values().removeIf(value -> value == 0L);
            } else if (this.singleAmount == 0L) {
                this.singleKey = null;
            }
        }

        @Override
        public void forEachEntry(KeyCounter.EntryConsumer consumer) {
            if (this.records != null) {
                super.forEachEntry(consumer);
            } else if (this.singleKey != null) {
                consumer.accept(this.singleKey, this.singleAmount);
            }
        }

        @Override
        public Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEKey filter, FuzzyMode fuzzy) {
            return entries();
        }

        /**
         * @return a live entry of {@code key} as the only element, or an empty collection if this map does not contain
         *         the key.
         */
        Collection<Object2LongMap.Entry<AEKey>> findPrecise(AEKey key) {
            boolean contains = this.records != null ? this.records.containsKey(key) : key.equals(this.singleKey);
            return contains ? Collections.singletonList(new SingleEntry(key)) : Collections.emptyList();
        }

        @Override
        Collection<Object2LongMap.Entry<AEKey>> entries() {
            if (this.entries == null) {
                // Resolve the backing storage on each access, including after promotion or clear.
                this.entries = new AbstractCollection<>() {

                    @Override
                    public Iterator<Object2LongMap.Entry<AEKey>> iterator() {
                        return records != null ? records.object2LongEntrySet().iterator() : singleIterator();
                    }

                    @Override
                    public int size() {
                        return UnorderedVariantMap.this.size();
                    }

                    @Override
                    public void clear() {
                        UnorderedVariantMap.this.clear();
                    }
                };
            }
            return this.entries;
        }

        @Override
        public Iterator<Object2LongMap.Entry<AEKey>> iterator() {
            return this.records != null ? Object2LongMaps.fastIterator(this.records) : singleIterator();
        }

        private Iterator<Object2LongMap.Entry<AEKey>> singleIterator() {
            return new Iterator<>() {

                private final @Nullable AEKey key = singleKey;
                private boolean consumed;
                private boolean canRemove;

                @Override
                public boolean hasNext() {
                    return this.key != null && !this.consumed;
                }

                @Override
                public Object2LongMap.Entry<AEKey> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    this.consumed = true;
                    this.canRemove = true;
                    return new SingleEntry(this.key);
                }

                @Override
                public void remove() {
                    if (!this.canRemove) {
                        throw new IllegalStateException();
                    }
                    UnorderedVariantMap.this.remove(this.key);
                    this.canRemove = false;
                }
            };
        }

        private final class SingleEntry implements Object2LongMap.Entry<AEKey> {

            private final AEKey key;

            private SingleEntry(AEKey key) {
                this.key = key;
            }

            @Override
            public AEKey getKey() {
                return this.key;
            }

            @Override
            public long getLongValue() {
                return get(this.key);
            }

            @Override
            public long setValue(long value) {
                long old = get(this.key);
                set(this.key, value);
                return old;
            }

            @Override
            public boolean equals(@Nullable Object other) {
                return other instanceof Map.Entry<?, ?>entry && this.key.equals(entry.getKey())
                        && entry.getValue() instanceof Long value
                        && getLongValue() == value.longValue();
            }

            @Override
            public int hashCode() {
                return this.key.hashCode() ^ Long.hashCode(getLongValue());
            }

            @Override
            public String toString() {
                return this.key + "=>" + getLongValue();
            }
        }

        @Override
        public VariantCounter copy() {
            UnorderedVariantMap result = new UnorderedVariantMap();
            if (this.records != null) {
                result.records = new AEKey2LongMap.OpenHashMap(this.records.size());
                result.records.putAll(this.records);
            } else {
                result.singleKey = this.singleKey;
                result.singleAmount = this.singleAmount;
            }
            return result;
        }
    }

    static final class FuzzyVariantMap extends VariantCounter {

        private final AEKey2LongMap.AVLTreeMap records = FuzzySearch.createMap2Long();

        @Override
        public long get(AEKey key) {
            return this.records.getLong(key);
        }

        @Override
        public void add(AEKey key, long amount) {
            this.records.addTo(key, amount);
        }

        @Override
        public void set(AEKey key, long amount) {
            this.records.put(key, amount);
        }

        @Override
        public long remove(AEKey key) {
            return this.records.removeLong(key);
        }

        @Override
        public int size() {
            return this.records.size();
        }

        @Override
        public void clear() {
            this.records.clear();
        }

        @Override
        public Iterator<Object2LongMap.Entry<AEKey>> iterator() {
            return Object2LongMaps.fastIterator(this.records);
        }

        @Override
        public Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEKey key, FuzzyMode fuzzy) {
            return FuzzySearch.findFuzzy(this.records, key, fuzzy).object2LongEntrySet();
        }

        @Override
        Collection<Object2LongMap.Entry<AEKey>> entries() {
            return this.records.object2LongEntrySet();
        }

        @Override
        public VariantCounter copy() {
            FuzzyVariantMap result = new FuzzyVariantMap();
            result.records.putAll(this.records);
            return result;
        }
    }
}
