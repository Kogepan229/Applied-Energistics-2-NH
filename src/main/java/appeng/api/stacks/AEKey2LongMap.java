package appeng.api.stacks;

import java.util.Comparator;

import it.unimi.dsi.fastutil.objects.Object2LongAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * Exposes {@code addTo} polymorphically. {@link Object2LongMap#mergeLong} is not used because the fastutil maps don't
 * override its naive implementation.
 */
interface AEKey2LongMap extends Object2LongMap<AEKey> {

    /**
     * Adds an increment to the value currently associated with a key.
     *
     * @return the old value, or the {@linkplain #defaultReturnValue() default return value} if no value was present.
     */
    @SuppressWarnings("UnusedReturnValue")
    long addTo(AEKey k, long incr);

    final class OpenHashMap extends Object2LongOpenHashMap<AEKey> implements AEKey2LongMap {

        OpenHashMap() {}

        OpenHashMap(int expected) {
            super(expected);
        }
    }

    final class AVLTreeMap extends Object2LongAVLTreeMap<AEKey> implements AEKey2LongMap {

        AVLTreeMap(Comparator<? super AEKey> c) {
            super(c);
        }
    }
}
