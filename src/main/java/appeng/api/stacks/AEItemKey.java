package appeng.api.stacks;

import java.io.IOException;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.oredict.OreDictionary;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import appeng.util.item.OreHelper;
import appeng.util.item.OreReference;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;
import io.netty.buffer.ByteBuf;

/**
 * Identifies an item by {@link Item}, damage and NBT. The NBT is interned through {@link InternedTag}, so equality of
 * tags is a reference comparison.
 */
public final class AEItemKey extends AEKey {

    private static final int DAMAGE_BITS = 16;

    private final Item item;
    private final int damage;
    private static final int NOT_COMPUTED = Integer.MIN_VALUE;

    /** The raw damage of damageable items, which sorted fuzzy maps query on every comparison. */
    private final int fuzzySearchValue;
    /**
     * The display damage and max damage of damageable items. They need a stack, and only fuzzy filters use them, so
     * they are computed on first use. The volatile max damage is written last and publishes both values.
     */
    private int fuzzyPartitionValue;
    private volatile int fuzzySearchMaxValue;
    /**
     * Read-only stack of size 1, created on first use so that keys which are only counted do not keep a copy of the
     * NBT. Its tag is a private copy, never the interned one, because some items write to NBT while being displayed.
     */
    private @Nullable ItemStack readonlyStack;
    private final @Nullable InternedTag tag;
    private @Nullable String modId;
    private final int hashCode;

    private AEItemKey(Item item, int damage, @Nullable InternedTag tag) {
        this.item = item;
        this.damage = damage;
        this.tag = tag;
        this.hashCode = (Item.getIdFromItem(item) << DAMAGE_BITS | damage) ^ (tag == null ? 0 : tag.hashCode());

        // Only damageable items use fuzzy damage ranges, and only they may need NBT to compute their durability.
        if (item.isDamageable()) {
            this.fuzzySearchValue = damage;
            this.fuzzySearchMaxValue = NOT_COMPUTED;
        } else {
            this.fuzzySearchValue = 0;
            this.fuzzyPartitionValue = 0;
            this.fuzzySearchMaxValue = 0;
        }
    }

    /**
     * @return the max damage, computing it together with the display damage on first use. Racing threads compute equal
     *         values, so publishing either of them is fine.
     */
    private int computeFuzzyValues() {
        final ItemStack stack = this.getReadonlyStack();
        this.fuzzyPartitionValue = getDisplayDamage(stack, this.damage);
        final int maxValue = stack.getMaxDamage();
        this.fuzzySearchMaxValue = maxValue;
        return maxValue;
    }

    /**
     * Some items throw from {@link ItemStack#getItemDamageForDisplay()}; fall back to the raw damage.
     */
    private static int getDisplayDamage(ItemStack stack, int fallback) {
        try {
            return stack.getItemDamageForDisplay();
        } catch (Throwable e) {
            return fallback;
        }
    }

    /**
     * Reads the raw damage value. {@link ItemStack#getItemDamage()} delegates to {@link Item#getDamage(ItemStack)},
     * which some items override; asking an item that does not override it returns the stored value.
     */
    private static int getRawDamage(ItemStack stack) {
        return Items.blaze_rod.getDamage(stack);
    }

    @Nullable
    public static AEItemKey of(@Nullable ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }

        return of(stack.getItem(), getRawDamage(stack), stack.getTagCompound());
    }

    public static AEItemKey of(Item item) {
        return of(item, 0, null);
    }

    public static AEItemKey of(Item item, int damage) {
        return of(item, damage, null);
    }

    public static AEItemKey of(Item item, int damage, @Nullable NBTTagCompound tag) {
        return new AEItemKey(item, damage, InternedTag.of(tag, false));
    }

    /**
     * Like {@link #of(Item, int, NBTTagCompound)}, for a tag that nothing else references, which saves a copy.
     */
    private static AEItemKey ofOwned(Item item, int damage, @Nullable NBTTagCompound tag) {
        return new AEItemKey(item, damage, InternedTag.of(tag, true));
    }

    @Nullable
    public static AEItemKey fromNBT(@Nullable NBTTagCompound input) {
        if (input == null || input.hasNoTags()) {
            return null;
        }

        final ItemStack stack = ItemStack.loadItemStackFromNBT(input);
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        // The loaded stack owns a fresh copy of the tag.
        return ofOwned(stack.getItem(), getRawDamage(stack), stack.getTagCompound());
    }

    @Nullable
    public static AEItemKey fromPacket(ByteBuf input) throws IOException {
        PacketBuffer pb = new PacketBuffer(input);
        int id = pb.readVarIntFromBuffer();
        int damage = pb.readShort();
        NBTTagCompound tag = pb.readNBTTagCompoundFromBuffer();

        Item item = Item.getItemById(id);
        return item == null ? null : ofOwned(item, damage, tag);
    }

    @Override
    public AEKeyType getType() {
        return AEKeyType.items();
    }

    @Override
    public AEItemKey dropSecondary() {
        return this.damage == 0 && this.tag == null ? this : of(this.item);
    }

    @Override
    public Item getPrimaryKey() {
        return this.item;
    }

    @Override
    protected void writeIdentityToNBT(NBTTagCompound output) {
        output.setShort("id", (short) Item.getIdFromItem(this.item));
        output.setShort("Damage", (short) this.damage);
        if (this.tag != null) {
            output.setTag("tag", this.tag.getTag().copy());
        }
    }

    @Override
    protected void writeIdentityToPacket(ByteBuf output) throws IOException {
        PacketBuffer pb = new PacketBuffer(output);
        pb.writeVarIntToBuffer(Item.getIdFromItem(this.item));
        pb.writeShort(this.damage);
        pb.writeNBTTagCompoundToBuffer(this.tag == null ? null : this.tag.getTag());
    }

    public Item getItem() {
        return this.item;
    }

    /**
     * @return a stack of size 1 that represents this key. It is shared and must not be modified; use {@link #toStack()}
     *         for a stack that can be changed.
     */
    public ItemStack getReadonlyStack() {
        ItemStack result = this.readonlyStack;
        if (result == null) {
            // Racing threads create equal stacks, so publishing either of them is fine.
            result = this.toStack();
            this.readonlyStack = result;
        }
        return result;
    }

    public int getItemDamage() {
        return this.damage;
    }

    @Override
    public int getFuzzySearchValue() {
        return this.fuzzySearchValue;
    }

    @Override
    public int getFuzzySearchMaxValue() {
        final int maxValue = this.fuzzySearchMaxValue;
        return maxValue != NOT_COMPUTED ? maxValue : this.computeFuzzyValues();
    }

    /**
     * The display damage, which may differ from the raw damage used by {@link #getFuzzySearchValue()}.
     */
    @Override
    int getFuzzyPartitionValue() {
        // Reading the volatile max damage first makes the partition value visible.
        if (this.fuzzySearchMaxValue == NOT_COMPUTED) {
            this.computeFuzzyValues();
        }
        return this.fuzzyPartitionValue;
    }

    /**
     * Same rules as {@link KeyCounter#findFuzzy}: if this key has OreDictionary entries, the registered ore stacks are
     * used as filters instead of this key. Ore stacks with the wildcard damage match every damage value; the others
     * match by fuzzy damage range if the item is damageable, otherwise by exact damage.
     */
    @Override
    public boolean fuzzyEquals(@Nullable AEKey other, FuzzyMode mode) {
        if (!(other instanceof AEItemKey candidate)) {
            return false;
        }

        final OreReference oreReference = OreHelper.INSTANCE.getReference(this);
        if (oreReference == null) {
            return this.fuzzyEqualsIgnoringOres(candidate, mode, false);
        }
        for (AEItemKey equivalentKey : oreReference.getAEEquivalentKeys()) {
            boolean ignoreMeta = equivalentKey.damage == OreDictionary.WILDCARD_VALUE;
            if (equivalentKey.fuzzyEqualsIgnoringOres(candidate, mode, ignoreMeta)) {
                return true;
            }
        }
        return false;
    }

    private boolean fuzzyEqualsIgnoringOres(AEItemKey candidate, FuzzyMode mode, boolean ignoreMeta) {
        if (candidate.item != this.item) {
            return false;
        }
        if (this.item.isDamageable()) {
            if (this.getFuzzySearchMaxValue() <= 0) {
                return candidate.damage == this.damage;
            }
            return ignoreMeta || FuzzySearch.isInRange(this, mode, candidate);
        }
        return ignoreMeta || candidate.damage == this.damage;
    }

    @Override
    public String getId() {
        return String.valueOf(Item.itemRegistry.getNameForObject(this.item));
    }

    @Override
    public String getModId() {
        if (this.modId == null) {
            UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(this.item);
            this.modId = uid == null || uid.modId == null ? "** Null" : uid.modId;
        }

        return this.modId;
    }

    @Override
    public String getDisplayName() {
        final ItemStack stack = this.getReadonlyStack();
        try {
            final String name = stack.getDisplayName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return String.valueOf(this.item.getUnlocalizedName(stack));
        } catch (Exception e) {
            try {
                return String.valueOf(stack.getUnlocalizedName());
            } catch (Exception e2) {
                return "** Exception";
            }
        }
    }

    @Override
    public IChatComponent getDisplayComponent() {
        return this.getReadonlyStack().func_151000_E();
    }

    @Override
    public boolean hasTag() {
        return this.tag != null;
    }

    /**
     * @return a mutable copy of the tag, or null if this key has no NBT.
     */
    @Nullable
    public NBTTagCompound getTag() {
        return this.tag == null ? null : (NBTTagCompound) this.tag.getTag().copy();
    }

    /**
     * @return the interned tag. Must not be modified.
     */
    @Nullable
    @ApiStatus.Internal
    public NBTTagCompound getInternalTag() {
        return this.tag == null ? null : this.tag.getTag();
    }

    public ItemStack toStack() {
        return this.toStack(1);
    }

    /**
     * @return a new stack of this key with a private copy of the tag.
     */
    public ItemStack toStack(long amount) {
        final ItemStack result = new ItemStack(this.item, Platform.longToInt(amount), this.damage);
        if (this.tag != null) {
            result.setTagCompound((NBTTagCompound) this.tag.getTag().copy());
        }
        return result;
    }

    @Override
    public IAEItemStack toAEStack(long amount) {
        // The created stack keeps its own copy of the tag, so the read-only stack can be passed.
        return AEItemStack.create(this.getReadonlyStack()).setStackSize(amount);
    }

    @Override
    public ItemStack wrapForDisplayOrFilter() {
        return this.wrapForDisplayOrFilter(1L);
    }

    @Override
    public ItemStack wrapForDisplayOrFilter(long amount) {
        return this.toStack(amount);
    }

    public boolean matches(@Nullable ItemStack other) {
        if (other == null || other.getItem() != this.item || getRawDamage(other) != this.damage) {
            return false;
        }

        final NBTTagCompound otherTag = other.getTagCompound();
        if (otherTag == null || otherTag.hasNoTags()) {
            return this.tag == null;
        }
        return this.tag != null && this.tag.getTag().equals(otherTag);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof AEItemKey other && this.item == other.item
                && this.damage == other.damage
                && this.tag == other.tag;
    }

    @Override
    public int hashCode() {
        return this.hashCode;
    }

    @Override
    public String toString() {
        return this.getReadonlyStack().toString();
    }
}
