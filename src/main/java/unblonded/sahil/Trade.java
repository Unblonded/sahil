package unblonded.sahil;

import net.minecraft.item.ItemStack;

public record Trade(ItemStack buy1, ItemStack buy2, ItemStack sell) {
    public Trade(ItemStack buy1, ItemStack sell) {
        this(buy1, ItemStack.EMPTY, sell);
    }

    public boolean hasSecondBuy() {
        return !buy2.isEmpty();
    }
}