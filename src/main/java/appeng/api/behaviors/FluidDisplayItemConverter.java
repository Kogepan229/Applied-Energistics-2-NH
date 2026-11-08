package appeng.api.behaviors;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jspecify.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.util.Platform;
import codechicken.nei.recipe.StackInfo;

/**
 * Converts fluid display items known to NEI (e.g. GT fluid displays) into fluids.
 */
final class FluidDisplayItemConverter implements DisplayItemConverter {

    @Override
    public @Nullable GenericStack convert(ItemStack stack) {
        FluidStack fluidStack = StackInfo.getFluid(Platform.copyStackWithSizeOne(stack));
        AEFluidKey key = AEFluidKey.of(fluidStack);
        return key == null ? null : new GenericStack(key, fluidStack.amount);
    }
}
