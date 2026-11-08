package appeng.api.stacks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraftforge.oredict.OreDictionary;

import org.jspecify.annotations.Nullable;

import appeng.api.config.FuzzyMode;
import appeng.util.item.OreHelper;
import appeng.util.item.OreReference;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

/**
 * Associates amounts with AE keys and makes key/amount pairs searchable with fuzzy mode semantics.
 */
public final class KeyCounter implements Iterable<Object2LongMap.Entry<AEKey>> {

    @FunctionalInterface
    public interface EntryConsumer {

        void accept(AEKey key, long amount);
    }

    private final Reference2ObjectMap<Item, ItemCounter> itemCounters = new Reference2ObjectOpenHashMap<>();
    /**
     * Counters for non-item keys of {@linkplain AEKeyType#hasVariants() types with variants}, grouped by primary key.
     */
    private final Reference2ObjectMap<Object, VariantCounter> simpleCounters = new Reference2ObjectOpenHashMap<>();
    /**
     * Keys of {@linkplain AEKeyType#hasVariants() types without variants}, such as fluids. Each of them is the only key
     * with its primary key, so they need no grouping.
     */
    private final VariantCounter.UnorderedVariantMap uniqueCounter = new VariantCounter.UnorderedVariantMap();

    public Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEKey key, FuzzyMode fuzzy) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fuzzy, "fuzzy");

        if (key instanceof AEItemKey itemKey) {
            return this.findFuzzyItem(itemKey, fuzzy);
        }
        if (!key.getType().hasVariants()) {
            return this.uniqueCounter.findPrecise(key);
        }

        VariantCounter counter = this.getSubIndexOrNull(key);
        return counter == null ? Collections.emptyList() : counter.findFuzzy(key, fuzzy);
    }

    public void removeZeros() {
        removeEmptySubmaps(true);
    }

    public void removeEmptySubmaps() {
        removeEmptySubmaps(false);
    }

    private void removeEmptySubmaps(boolean removeZeros) {
        Iterator<ItemCounter> itemIterator = this.itemCounters.values().iterator();
        while (itemIterator.hasNext()) {
            ItemCounter counter = itemIterator.next();
            counter.removeEmptySubmaps(removeZeros);
            if (counter.isEmpty()) {
                itemIterator.remove();
            }
        }

        Iterator<VariantCounter> simpleIterator = this.simpleCounters.values().iterator();
        while (simpleIterator.hasNext()) {
            VariantCounter counter = simpleIterator.next();
            if (removeZeros) {
                counter.removeZeros();
            }
            if (counter.isEmpty()) {
                simpleIterator.remove();
            }
        }
        if (removeZeros) {
            this.uniqueCounter.removeZeros();
        }
    }

    public void addAll(KeyCounter other) {
        merge(other, false);
    }

    public void removeAll(KeyCounter other) {
        merge(other, true);
    }

    private void merge(KeyCounter other, boolean subtract) {
        Objects.requireNonNull(other, "other");
        for (Reference2ObjectMap.Entry<Item, ItemCounter> entry : other.itemCounters.reference2ObjectEntrySet()) {
            ItemCounter counter = this.itemCounters.get(entry.getKey());
            if (counter != null) {
                counter.merge(entry.getValue(), subtract);
                continue;
            }
            ItemCounter copy = entry.getValue().copy();
            if (subtract) {
                copy.invert();
            }
            this.itemCounters.put(entry.getKey(), copy);
        }
        for (Reference2ObjectMap.Entry<Object, VariantCounter> entry : other.simpleCounters
                .reference2ObjectEntrySet()) {
            VariantCounter counter = this.simpleCounters.get(entry.getKey());
            if (counter != null) {
                mergeInto(counter, entry.getValue(), subtract);
                continue;
            }
            VariantCounter copy = entry.getValue().copy();
            if (subtract) {
                copy.invert();
            }
            this.simpleCounters.put(entry.getKey(), copy);
        }
        mergeInto(this.uniqueCounter, other.uniqueCounter, subtract);
    }

    private static void mergeInto(VariantCounter target, VariantCounter source, boolean subtract) {
        if (subtract) {
            target.removeAll(source);
        } else {
            target.addAll(source);
        }
    }

    public void add(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        VariantCounter counter = getSubIndex(key);
        counter.add(key, amount);
    }

    /**
     * Subtracts the given amount from the value associated with the given key.
     */
    public void remove(AEKey key, long amount) {
        add(key, -amount);
    }

    /**
     * Removes the given key from this counter, and returns the old value (or 0).
     */
    public long remove(AEKey key) {
        Objects.requireNonNull(key, "key");
        VariantCounter counter = getSubIndexOrNull(key);
        if (counter == null) {
            return 0L;
        }

        long removed = counter.remove(key);
        if (counter.isEmpty()) {
            removeEmptyCounter(key);
        }
        return removed;
    }

    public void set(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        getSubIndex(key).set(key, amount);
    }

    public long get(AEKey key) {
        Objects.requireNonNull(key, "key");
        VariantCounter counter = getSubIndexOrNull(key);
        return counter == null ? 0L : counter.get(key);
    }

    public void reset() {
        for (ItemCounter counter : this.itemCounters.values()) {
            counter.reset();
        }
        for (VariantCounter counter : this.simpleCounters.values()) {
            counter.reset();
        }
        this.uniqueCounter.reset();
    }

    /**
     * Clears entries but keeps existing buckets for reuse.
     */
    public void clear() {
        for (ItemCounter counter : this.itemCounters.values()) {
            counter.clear();
        }
        for (VariantCounter counter : this.simpleCounters.values()) {
            counter.clear();
        }
        this.uniqueCounter.clear();
    }

    public boolean isEmpty() {
        for (ItemCounter counter : this.itemCounters.values()) {
            if (!counter.isEmpty()) {
                return false;
            }
        }
        for (VariantCounter counter : this.simpleCounters.values()) {
            if (!counter.isEmpty()) {
                return false;
            }
        }
        return this.uniqueCounter.isEmpty();
    }

    public int size() {
        int total = 0;
        for (ItemCounter counter : this.itemCounters.values()) {
            total += counter.size();
        }
        for (VariantCounter counter : this.simpleCounters.values()) {
            total += counter.size();
        }
        total += this.uniqueCounter.size();
        return total;
    }

    @Override
    public Iterator<Object2LongMap.Entry<AEKey>> iterator() {
        return new CounterIterator();
    }

    public void forEachEntry(EntryConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (ItemCounter counter : this.itemCounters.values()) {
            counter.forEachEntry(consumer);
        }
        for (VariantCounter counter : this.simpleCounters.values()) {
            counter.forEachEntry(consumer);
        }
        this.uniqueCounter.forEachEntry(consumer);
    }

    @Nullable
    public AEKey getFirstKey() {
        Object2LongMap.Entry<AEKey> entry = getFirstEntry();
        return entry == null ? null : entry.getKey();
    }

    @Nullable
    public <T extends AEKey> T getFirstKey(Class<T> keyClass) {
        Object2LongMap.Entry<AEKey> entry = getFirstEntry(keyClass);
        return entry == null ? null : keyClass.cast(entry.getKey());
    }

    public Object2LongMap.@Nullable Entry<AEKey> getFirstEntry() {
        Iterator<Object2LongMap.Entry<AEKey>> iterator = iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    public <T extends AEKey> Object2LongMap.@Nullable Entry<AEKey> getFirstEntry(Class<T> keyClass) {
        Objects.requireNonNull(keyClass, "keyClass");
        for (Object2LongMap.Entry<AEKey> entry : this) {
            if (keyClass.isInstance(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }

    public Set<AEKey> keySet() {
        Set<AEKey> keys = new HashSet<>(size());
        this.forEachEntry((key, amount) -> keys.add(key));
        return keys;
    }

    private Collection<Object2LongMap.Entry<AEKey>> findFuzzyItem(AEItemKey key, FuzzyMode fuzzy) {
        OreReference oreReference = OreHelper.INSTANCE.getReference(key);
        if (oreReference == null) {
            return findFuzzyItem(key, fuzzy, false);
        }

        Collection<AEItemKey> equivalentKeys = oreReference.getAEEquivalentKeys();
        if (equivalentKeys.size() == 1) {
            AEItemKey equivalentKey = equivalentKeys.iterator().next();
            return findFuzzyItem(equivalentKey, fuzzy, equivalentKey.getItemDamage() == OreDictionary.WILDCARD_VALUE);
        }

        ArrayList<Object2LongMap.Entry<AEKey>> output = new ArrayList<>();
        ObjectOpenHashSet<AEKey> seen = new ObjectOpenHashSet<>();
        for (AEItemKey equivalentKey : equivalentKeys) {
            boolean ignoreMeta = equivalentKey.getItemDamage() == OreDictionary.WILDCARD_VALUE;
            for (Object2LongMap.Entry<AEKey> entry : findFuzzyItem(equivalentKey, fuzzy, ignoreMeta)) {
                if (seen.add(entry.getKey())) {
                    output.add(entry);
                }
            }
        }
        return output;
    }

    private Collection<Object2LongMap.Entry<AEKey>> findFuzzyItem(AEItemKey key, FuzzyMode fuzzy, boolean ignoreMeta) {
        ItemCounter counter = this.itemCounters.get(key.getItem());
        return counter == null ? Collections.emptyList() : counter.findFuzzy(key, fuzzy, ignoreMeta);
    }

    private VariantCounter getSubIndex(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            Item item = itemKey.getItem();
            ItemCounter counter = this.itemCounters.get(item);
            if (counter == null) {
                counter = new ItemCounter(item);
                this.itemCounters.put(item, counter);
            }
            return counter.getSubIndex(itemKey);
        }
        if (!key.getType().hasVariants()) {
            return this.uniqueCounter;
        }

        Object primaryKey = key.getPrimaryKey();
        VariantCounter counter = this.simpleCounters.get(primaryKey);
        if (counter == null) {
            counter = new VariantCounter.UnorderedVariantMap();
            this.simpleCounters.put(primaryKey, counter);
        }
        return counter;
    }

    @Nullable
    private VariantCounter getSubIndexOrNull(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            ItemCounter counter = this.itemCounters.get(itemKey.getItem());
            return counter == null ? null : counter.getSubIndexOrNull(itemKey);
        }
        if (!key.getType().hasVariants()) {
            return this.uniqueCounter;
        }

        return this.simpleCounters.get(key.getPrimaryKey());
    }

    private void removeEmptyCounter(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            ItemCounter counter = this.itemCounters.get(itemKey.getItem());
            if (counter != null) {
                counter.removeEmptySubIndex(itemKey);
                if (counter.isEmpty()) {
                    this.itemCounters.remove(itemKey.getItem());
                }
            }
        } else if (key.getType().hasVariants()) {
            Object primaryKey = key.getPrimaryKey();
            VariantCounter counter = this.simpleCounters.get(primaryKey);
            if (counter != null && counter.isEmpty()) {
                this.simpleCounters.remove(primaryKey);
            }
        }
    }

    private static final class ItemCounter implements Iterable<Object2LongMap.Entry<AEKey>> {

        private final boolean damageable;
        private final Item item;
        /**
         * The only sub-index: the fuzzy map of a damageable item, or the counter of the only metadata in use.
         */
        private @Nullable VariantCounter singleCounter;
        /** The metadata of {@link #singleCounter}. Unused for damageable items. */
        private int singleMetadata;
        /** Sub-indexes by metadata, once a non-damageable item uses more than one. Replaces {@link #singleCounter}. */
        private @Nullable Int2ObjectMap<VariantCounter> metadataCounters;

        private ItemCounter(Item item) {
            this.item = item;
            this.damageable = item.isDamageable();
        }

        private ItemCounter copy() {
            ItemCounter result = new ItemCounter(this.item);
            result.singleMetadata = this.singleMetadata;
            if (this.singleCounter != null) {
                result.singleCounter = this.singleCounter.copy();
            }
            if (this.metadataCounters != null) {
                result.metadataCounters = new Int2ObjectOpenHashMap<>(this.metadataCounters.size());
                for (Int2ObjectMap.Entry<VariantCounter> entry : this.metadataCounters.int2ObjectEntrySet()) {
                    result.metadataCounters.put(entry.getIntKey(), entry.getValue().copy());
                }
            }
            return result;
        }

        private void merge(ItemCounter other, boolean subtract) {
            if (other.singleCounter != null) {
                VariantCounter target = this.damageable ? getDamageableSubIndex()
                        : getMetadataSubIndex(other.singleMetadata);
                mergeInto(target, other.singleCounter, subtract);
            }
            if (other.metadataCounters != null) {
                for (Int2ObjectMap.Entry<VariantCounter> entry : other.metadataCounters.int2ObjectEntrySet()) {
                    mergeInto(getMetadataSubIndex(entry.getIntKey()), entry.getValue(), subtract);
                }
            }
        }

        private void invert() {
            if (this.singleCounter != null) {
                this.singleCounter.invert();
            }
            if (this.metadataCounters != null) {
                for (VariantCounter counter : this.metadataCounters.values()) {
                    counter.invert();
                }
            }
        }

        private VariantCounter getSubIndex(AEItemKey key) {
            if (this.damageable) {
                return getDamageableSubIndex();
            }

            return getMetadataSubIndex(key.getItemDamage());
        }

        private VariantCounter getDamageableSubIndex() {
            if (this.singleCounter == null) {
                this.singleCounter = new VariantCounter.FuzzyVariantMap();
            }
            return this.singleCounter;
        }

        private VariantCounter getMetadataSubIndex(int metadata) {
            if (this.metadataCounters != null) {
                VariantCounter counter = this.metadataCounters.get(metadata);
                if (counter == null) {
                    counter = new VariantCounter.UnorderedVariantMap();
                    this.metadataCounters.put(metadata, counter);
                }
                return counter;
            }

            if (this.singleCounter == null) {
                this.singleMetadata = metadata;
                this.singleCounter = new VariantCounter.UnorderedVariantMap();
                return this.singleCounter;
            }

            if (this.singleMetadata == metadata) {
                return this.singleCounter;
            }

            this.metadataCounters = new Int2ObjectOpenHashMap<>();
            this.metadataCounters.put(this.singleMetadata, this.singleCounter);
            this.singleCounter = null;

            VariantCounter counter = new VariantCounter.UnorderedVariantMap();
            this.metadataCounters.put(metadata, counter);
            return counter;
        }

        @Nullable
        private VariantCounter getSubIndexOrNull(AEItemKey key) {
            if (this.damageable) {
                return this.singleCounter;
            }
            if (this.metadataCounters == null) {
                return this.singleCounter != null && this.singleMetadata == key.getItemDamage() ? this.singleCounter
                        : null;
            }
            return this.metadataCounters.get(key.getItemDamage());
        }

        private Collection<Object2LongMap.Entry<AEKey>> findFuzzy(AEItemKey key, FuzzyMode fuzzy, boolean ignoreMeta) {
            if (this.damageable) {
                VariantCounter counter = this.singleCounter;
                if (counter == null) {
                    return Collections.emptyList();
                }
                if (key.getFuzzySearchMaxValue() <= 0) {
                    return findSameDamage(counter, key);
                }
                return ignoreMeta ? counter.entries() : counter.findFuzzy(key, fuzzy);
            }

            if (ignoreMeta) {
                return combineAllMetadataEntries();
            }

            VariantCounter counter = getSubIndexOrNull(key);
            return counter == null ? Collections.emptyList() : counter.findFuzzy(key, fuzzy);
        }

        private Collection<Object2LongMap.Entry<AEKey>> findSameDamage(VariantCounter counter, AEItemKey key) {
            ArrayList<Object2LongMap.Entry<AEKey>> output = new ArrayList<>();
            int damage = key.getItemDamage();
            for (Object2LongMap.Entry<AEKey> entry : counter.entries()) {
                if (entry.getKey() instanceof AEItemKey itemKey && itemKey.getItemDamage() == damage) {
                    output.add(entry);
                }
            }
            return output;
        }

        private Collection<Object2LongMap.Entry<AEKey>> combineAllMetadataEntries() {
            if (this.singleCounter != null) {
                return this.singleCounter.entries();
            }
            if (this.metadataCounters == null || this.metadataCounters.isEmpty()) {
                return Collections.emptyList();
            }
            if (this.metadataCounters.size() == 1) {
                return this.metadataCounters.values().iterator().next().entries();
            }

            ArrayList<Object2LongMap.Entry<AEKey>> output = new ArrayList<>();
            for (VariantCounter counter : this.metadataCounters.values()) {
                output.addAll(counter.entries());
            }
            return output;
        }

        private void removeEmptySubmaps(boolean removeZeros) {
            if (this.singleCounter != null) {
                if (removeZeros) {
                    this.singleCounter.removeZeros();
                }
                if (this.singleCounter.isEmpty()) {
                    this.singleCounter = null;
                }
            }
            if (this.metadataCounters != null) {
                Iterator<VariantCounter> iterator = this.metadataCounters.values().iterator();
                while (iterator.hasNext()) {
                    VariantCounter counter = iterator.next();
                    if (removeZeros) {
                        counter.removeZeros();
                    }
                    if (counter.isEmpty()) {
                        iterator.remove();
                    }
                }
                this.compactMetadataCounters();
            }
        }

        /**
         * Drops only the empty sub-index of {@code key}, without scanning the other metadata of the item.
         */
        private void removeEmptySubIndex(AEItemKey key) {
            if (this.metadataCounters == null) {
                this.singleCounter = null;
            } else {
                this.metadataCounters.remove(key.getItemDamage());
                this.compactMetadataCounters();
            }
        }

        private void compactMetadataCounters() {
            if (this.metadataCounters == null) {
                return;
            }
            if (this.metadataCounters.isEmpty()) {
                this.metadataCounters = null;
            } else if (this.metadataCounters.size() == 1) {
                Int2ObjectMap.Entry<VariantCounter> entry = this.metadataCounters.int2ObjectEntrySet().iterator()
                        .next();
                this.singleMetadata = entry.getIntKey();
                this.singleCounter = entry.getValue();
                this.metadataCounters = null;
            }
        }

        private void reset() {
            if (this.singleCounter != null) {
                this.singleCounter.reset();
            }
            if (this.metadataCounters != null) {
                for (VariantCounter counter : this.metadataCounters.values()) {
                    counter.reset();
                }
            }
        }

        private void clear() {
            if (this.singleCounter != null) {
                this.singleCounter.clear();
            }
            if (this.metadataCounters != null) {
                for (VariantCounter counter : this.metadataCounters.values()) {
                    counter.clear();
                }
            }
        }

        private boolean isEmpty() {
            if (this.singleCounter != null && !this.singleCounter.isEmpty()) {
                return false;
            }
            if (this.metadataCounters != null) {
                for (VariantCounter counter : this.metadataCounters.values()) {
                    if (!counter.isEmpty()) {
                        return false;
                    }
                }
            }
            return true;
        }

        private int size() {
            int size = this.singleCounter == null ? 0 : this.singleCounter.size();
            if (this.metadataCounters != null) {
                for (VariantCounter counter : this.metadataCounters.values()) {
                    size += counter.size();
                }
            }
            return size;
        }

        @Override
        public Iterator<Object2LongMap.Entry<AEKey>> iterator() {
            return new ItemCounterIterator();
        }

        private void forEachEntry(EntryConsumer consumer) {
            if (this.singleCounter != null) {
                this.singleCounter.forEachEntry(consumer);
            }
            if (this.metadataCounters != null) {
                for (VariantCounter counter : this.metadataCounters.values()) {
                    counter.forEachEntry(consumer);
                }
            }
        }

        private final class ItemCounterIterator implements Iterator<Object2LongMap.Entry<AEKey>> {

            private boolean singleVisited;
            private final Iterator<VariantCounter> metadataIterator = ItemCounter.this.metadataCounters == null
                    ? Collections.emptyIterator()
                    : ItemCounter.this.metadataCounters.values().iterator();
            private Iterator<Object2LongMap.Entry<AEKey>> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!this.current.hasNext()) {
                    if (!this.singleVisited) {
                        this.singleVisited = true;
                        if (ItemCounter.this.singleCounter != null) {
                            this.current = ItemCounter.this.singleCounter.iterator();
                            continue;
                        }
                    }
                    if (!this.metadataIterator.hasNext()) {
                        return false;
                    }
                    this.current = this.metadataIterator.next().iterator();
                }
                return true;
            }

            @Override
            public Object2LongMap.Entry<AEKey> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return this.current.next();
            }

            @Override
            public void remove() {
                this.current.remove();
            }
        }
    }

    private final class CounterIterator implements Iterator<Object2LongMap.Entry<AEKey>> {

        private final Iterator<ItemCounter> itemIterator = KeyCounter.this.itemCounters.values().iterator();
        private final Iterator<VariantCounter> simpleIterator = KeyCounter.this.simpleCounters.values().iterator();
        private Iterator<Object2LongMap.Entry<AEKey>> current = Collections.emptyIterator();
        private int phase;

        @Override
        public boolean hasNext() {
            while (!this.current.hasNext()) {
                if (this.phase == 0) {
                    if (this.itemIterator.hasNext()) {
                        this.current = this.itemIterator.next().iterator();
                        continue;
                    }
                    this.phase = 1;
                }
                if (this.phase == 1) {
                    if (this.simpleIterator.hasNext()) {
                        this.current = this.simpleIterator.next().iterator();
                        continue;
                    }
                    this.phase = 2;
                    this.current = KeyCounter.this.uniqueCounter.iterator();
                    continue;
                }
                return false;
            }
            return true;
        }

        @Override
        public Object2LongMap.Entry<AEKey> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return this.current.next();
        }

        @Override
        public void remove() {
            this.current.remove();
        }
    }
}
