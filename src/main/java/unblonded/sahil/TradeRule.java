package unblonded.sahil;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public record TradeRule(Item buyItem, int maxBuyCount, Item sellItem) {
    /** maxBuyCount = -1 means "any quantity" */
    public boolean matchesBuySide(ItemStack buy) {
        if (!buy.isOf(buyItem)) return false;
        if (maxBuyCount == -1) return true;
        return buy.getCount() <= maxBuyCount;
    }

    public boolean matchesSellSide(ItemStack sell) {
        return sell.isOf(sellItem) && !sell.isEmpty();
    }
}