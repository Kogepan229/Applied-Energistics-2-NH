package appeng.api.stacks;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import net.minecraft.nbt.NBTTagCompound;

import org.jspecify.annotations.Nullable;

/**
 * An NBT tag shared by all keys whose tags are equal, so that keys can compare tags by reference.
 * <p>
 * The wrapped tag is owned by this object and must never be modified. Instances are released once no key references
 * them anymore.
 */
final class InternedTag {

    private static final WeakHashMap<InternedTag, WeakReference<InternedTag>> INTERNED = new WeakHashMap<>();

    private final NBTTagCompound tag;
    private final int hashCode;

    private InternedTag(NBTTagCompound tag, int hashCode) {
        this.tag = tag;
        this.hashCode = hashCode;
    }

    /**
     * @param tag           the tag to intern
     * @param giveOwnership whether {@code tag} may be kept as is because nothing else references it; otherwise a copy
     *                      is kept
     * @return the shared instance equal to {@code tag}, or null if {@code tag} is null or empty
     */
    static @Nullable InternedTag of(@Nullable NBTTagCompound tag, boolean giveOwnership) {
        if (tag == null || tag.hasNoTags()) {
            return null;
        }

        if (tag.getClass() != NBTTagCompound.class) {
            // Subclasses may override equals/hashCode; keep a plain copy instead.
            tag = (NBTTagCompound) tag.copy();
            giveOwnership = true;
        }

        // Hashing walks the whole tree, so do it once and outside the lock.
        final InternedTag searchHolder = new InternedTag(tag, tag.hashCode());
        synchronized (INTERNED) {
            final WeakReference<InternedTag> ref = INTERNED.get(searchHolder);
            InternedTag result = ref == null ? null : ref.get();
            if (result == null) {
                result = giveOwnership ? searchHolder
                        : new InternedTag((NBTTagCompound) tag.copy(), searchHolder.hashCode);
                INTERNED.put(result, new WeakReference<>(result));
            }
            return result;
        }
    }

    /**
     * @return the shared tag. Must not be modified.
     */
    NBTTagCompound getTag() {
        return this.tag;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof InternedTag other && this.hashCode == other.hashCode && this.tag.equals(other.tag);
    }

    @Override
    public int hashCode() {
        return this.hashCode;
    }
}
