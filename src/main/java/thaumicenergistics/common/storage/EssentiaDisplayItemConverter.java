package thaumicenergistics.common.storage;

import net.minecraft.item.ItemStack;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import appeng.api.behaviors.DisplayItemConverter;
import appeng.api.stacks.GenericStack;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumicenergistics.common.integration.ModsList;

/**
 * Converts aspect items of Aspect Recipe Index and Thaumcraft NEI Plugin into essentia. The stack size is the amount.
 */
@NullMarked
public final class EssentiaDisplayItemConverter implements DisplayItemConverter {

    @Override
    public @Nullable GenericStack convert(ItemStack stack) {
        final Aspect aspect = getAspect(stack);
        return aspect == null ? null : new GenericStack(AEEssentiaKey.of(aspect), stack.stackSize);
    }

    private static @Nullable Aspect getAspect(ItemStack stack) {
        if (ModsList.ASPECT_RECIPE_INDEX.isLoaded()
                && stack.getItem() instanceof com.gtnewhorizons.aspectrecipeindex.common.items.ItemAspect) {
            return com.gtnewhorizons.aspectrecipeindex.common.items.ItemAspect.getAspect(stack);
        }
        if (ModsList.THAUMCRAFT_NEI_PLUGIN.isLoaded()
                && stack.getItem() instanceof com.djgiannuzz.thaumcraftneiplugin.items.ItemAspect) {
            final AspectList aspects = com.djgiannuzz.thaumcraftneiplugin.items.ItemAspect.getAspects(stack);
            if (aspects != null && aspects.getAspects().length > 0) {
                return aspects.getAspects()[0];
            }
        }
        return null;
    }
}
