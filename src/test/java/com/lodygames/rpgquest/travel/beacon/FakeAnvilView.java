package com.lodygames.rpgquest.travel.beacon;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryView.Property;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

/**
 * Double de test minimal pour {@link AnvilView} (issue #150) : cette version de MockBukkit ne
 * fournit aucune implémentation simulée de {@code AnvilView}, pourtant exigée par le constructeur
 * de {@code PrepareAnvilEvent}. Seuls {@link #getTopInventory()} et {@link #getPlayer()} sont
 * réellement exercés par {@code TravelBeaconService#handlePrepareAnvil} (via {@code
 * event.getInventory()}/{@code event.getView().getPlayer()}) ; le reste n'est jamais appelé par le
 * code sous test et lève volontairement pour le signaler si ça changeait.
 */
final class FakeAnvilView implements AnvilView {

    private final AnvilInventory inventory;
    private final HumanEntity player;

    FakeAnvilView(AnvilInventory inventory, HumanEntity player) {
        this.inventory = inventory;
        this.player = player;
    }

    @Override
    public AnvilInventory getTopInventory() {
        return inventory;
    }

    @Override
    public HumanEntity getPlayer() {
        return player;
    }

    @Override
    public String getRenameText() {
        return inventory.getRenameText();
    }

    @Override
    public int getRepairItemCountCost() {
        return 0;
    }

    @Override
    public int getRepairCost() {
        return inventory.getRepairCost();
    }

    @Override
    public int getMaximumRepairCost() {
        return inventory.getMaximumRepairCost();
    }

    @Override
    public void setRepairItemCountCost(int cost) {
        // jamais appelé par le code sous test.
    }

    @Override
    public void setRepairCost(int cost) {
        inventory.setRepairCost(cost);
    }

    @Override
    public void setMaximumRepairCost(int cost) {
        inventory.setMaximumRepairCost(cost);
    }

    @Override
    public boolean bypassesEnchantmentLevelRestriction() {
        return false;
    }

    @Override
    public void bypassEnchantmentLevelRestriction(boolean bypass) {
        // jamais appelé par le code sous test.
    }

    @Override
    public Inventory getBottomInventory() {
        throw new UnsupportedOperationException("non utilisé par TravelBeaconService");
    }

    @Override
    public InventoryType getType() {
        return InventoryType.ANVIL;
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    @Override
    public ItemStack getItem(int slot) {
        return inventory.getItem(slot);
    }

    @Override
    public void setCursor(ItemStack item) {
        throw new UnsupportedOperationException("non utilisé par TravelBeaconService");
    }

    @Override
    public ItemStack getCursor() {
        throw new UnsupportedOperationException("non utilisé par TravelBeaconService");
    }

    @Override
    public Inventory getInventory(int rawSlot) {
        throw new UnsupportedOperationException("non utilisé par TravelBeaconService");
    }

    @Override
    public int convertSlot(int rawSlot) {
        return rawSlot;
    }

    @Override
    public InventoryType.SlotType getSlotType(int slot) {
        throw new UnsupportedOperationException("non utilisé par TravelBeaconService");
    }

    @Override
    public void open() {
        // jamais appelé par le code sous test.
    }

    @Override
    public void close() {
        // jamais appelé par le code sous test.
    }

    @Override
    public int countSlots() {
        return inventory.getSize();
    }

    @Override
    public boolean setProperty(Property property, int value) {
        return false;
    }

    @Override
    public String getTitle() {
        return "Rechercher un waypoint";
    }

    @Override
    public String getOriginalTitle() {
        return "Rechercher un waypoint";
    }

    @Override
    public void setTitle(String title) {
        // jamais appelé par le code sous test.
    }

    @Override
    public MenuType getMenuType() {
        throw new UnsupportedOperationException("non utilisé par TravelBeaconService");
    }
}
