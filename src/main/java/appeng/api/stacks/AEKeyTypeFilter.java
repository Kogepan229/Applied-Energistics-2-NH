package appeng.api.stacks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import org.jspecify.annotations.Nullable;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Reference2BooleanMap;
import it.unimi.dsi.fastutil.objects.Reference2BooleanMaps;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;

/**
 * Selection of enabled {@link AEKeyType}s, saved by type ID. The NBT format must stay readable for data that was saved
 * before keys existed. Copy before retaining a mutable selection as a query identity.
 */
public class AEKeyTypeFilter {

    public static final String NBT_FILTERS = "typeFilters";
    private static final String NBT_TYPE_ID = "typeId";
    private static final String NBT_VALUE = "value";

    private final Reference2BooleanMap<AEKeyType> filters;

    public AEKeyTypeFilter() {
        this.filters = createDefaultMap();
    }

    public AEKeyTypeFilter(AEKeyTypeFilter other) {
        this.filters = new Reference2BooleanOpenHashMap<>(other.filters);
    }

    /**
     * Reads the representation written by {@link #writeToPacket(ByteBuf)}.
     */
    public AEKeyTypeFilter(ByteBuf buf) {
        this.filters = createDefaultMap();

        final int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            final String typeId = ByteBufUtils.readUTF8String(buf);
            final boolean value = buf.readBoolean();
            final AEKeyType type = AEKeyTypeRegistry.get(typeId);
            if (type != null) {
                this.filters.put(type, value);
            }
        }
    }

    private static Reference2BooleanMap<AEKeyType> createDefaultMap() {
        final Reference2BooleanMap<AEKeyType> map = new Reference2BooleanOpenHashMap<>();
        for (AEKeyType type : AEKeyTypeRegistry.getAllTypes()) {
            map.put(type, true);
        }
        return map;
    }

    private void resetToDefault() {
        this.filters.clear();
        this.filters.putAll(createDefaultMap());
    }

    public void readFromNBT(NBTTagCompound tag) {
        if (!tag.hasKey(NBT_FILTERS)) {
            return;
        }

        this.resetToDefault();

        final NBTTagList list = tag.getTagList(NBT_FILTERS, NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            final NBTTagCompound entryTag = list.getCompoundTagAt(i);
            final AEKeyType type = AEKeyTypeRegistry.get(entryTag.getString(NBT_TYPE_ID));
            if (type != null) {
                this.filters.put(type, entryTag.getBoolean(NBT_VALUE));
            }
        }
    }

    public void writeToNBT(NBTTagCompound tag) {
        final NBTTagList list = new NBTTagList();
        for (Reference2BooleanMap.Entry<AEKeyType> entry : this.filters.reference2BooleanEntrySet()) {
            if (entry.getBooleanValue()) {
                continue; // enabled is the default
            }

            final NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setString(NBT_TYPE_ID, entry.getKey().getId());
            entryTag.setBoolean(NBT_VALUE, false);
            list.appendTag(entryTag);
        }

        tag.setTag(NBT_FILTERS, list);
    }

    public void writeToPacket(ByteBuf buf) {
        buf.writeInt(this.filters.size());
        for (Reference2BooleanMap.Entry<AEKeyType> entry : this.filters.reference2BooleanEntrySet()) {
            ByteBufUtils.writeUTF8String(buf, entry.getKey().getId());
            buf.writeBoolean(entry.getBooleanValue());
        }
    }

    public boolean isEnabled(AEKeyType type) {
        return this.filters.getBoolean(type);
    }

    /**
     * @return whether the type of {@code key} is enabled.
     */
    public boolean matches(AEKey key) {
        return this.isEnabled(key.getType());
    }

    public void copyFrom(AEKeyTypeFilter other) {
        this.resetToDefault();
        this.filters.putAll(other.filters);
    }

    public Reference2BooleanMap<AEKeyType> getKeyTypeFilters() {
        return Reference2BooleanMaps.unmodifiable(new Reference2BooleanOpenHashMap<>(this.filters));
    }

    public void setEnabled(AEKeyType type, boolean enabled) {
        this.filters.put(type, enabled);
    }

    public boolean toggle(AEKeyType type) {
        final boolean next = !this.filters.getBoolean(type);
        this.filters.put(type, next);
        return next;
    }

    public void setOnlyEnabled(AEKeyType targetType) {
        for (AEKeyType type : AEKeyTypeRegistry.getAllTypes()) {
            this.filters.put(type, type == targetType);
        }
    }

    public void setAllEnabled(boolean enabled) {
        for (AEKeyType type : AEKeyTypeRegistry.getAllTypes()) {
            this.filters.put(type, enabled);
        }
    }

    public List<AEKeyType> getEnabledKeyTypes() {
        final List<AEKeyType> enabledTypes = new ArrayList<>();
        for (AEKeyType type : AEKeyTypeRegistry.getAllTypes()) {
            if (this.filters.getBoolean(type)) {
                enabledTypes.add(type);
            }
        }
        return enabledTypes;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof AEKeyTypeFilter other && this.filters.equals(other.filters);
    }

    @Override
    public int hashCode() {
        return this.filters.hashCode();
    }
}
