package com.lodygames.rpgquest.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemArraySerializerTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void roundTripsEmptyArray() throws IOException {
        ItemStack[] original = new ItemStack[27];
        byte[] bytes = ItemArraySerializer.serialize(original);
        ItemStack[] decoded = ItemArraySerializer.deserialize(bytes);
        assertEquals(27, decoded.length);
        for (ItemStack item : decoded) {
            assertNull(item);
        }
    }

    @Test
    void roundTripsMixedContentPreservingMetaAndEmptySlots() throws IOException {
        ItemStack[] original = new ItemStack[9];
        original[0] = new ItemStack(Material.DIAMOND, 5);
        ItemStack named = new ItemStack(Material.IRON_SWORD);
        var meta = named.getItemMeta();
        meta.displayName(Component.text("Épée nommée"));
        named.setItemMeta(meta);
        original[3] = named;
        // cases 1, 2, 4-8 restent vides (null) : "inventaire plein" côtoie des cases vides ailleurs.

        byte[] bytes = ItemArraySerializer.serialize(original);
        ItemStack[] decoded = ItemArraySerializer.deserialize(bytes);

        assertEquals(9, decoded.length);
        assertEquals(new ItemStack(Material.DIAMOND, 5), decoded[0]);
        assertNull(decoded[1]);
        assertEquals(Material.IRON_SWORD, decoded[3].getType());
        assertEquals(Component.text("Épée nommée"), decoded[3].getItemMeta().displayName());
    }

    @Test
    void roundTripsACompletelyFullInventory() throws IOException {
        // "inventaire plein" : aucune case vide, toutes différentes pour vérifier qu'aucun slot n'est confondu.
        ItemStack[] original = new ItemStack[54];
        Material[] materials = java.util.Arrays.stream(Material.values())
                .filter(m -> !m.isAir() && m.isItem()).toArray(Material[]::new);
        for (int i = 0; i < original.length; i++) {
            original[i] = new ItemStack(materials[i % materials.length], (i % 64) + 1);
        }

        byte[] bytes = ItemArraySerializer.serialize(original);
        ItemStack[] decoded = ItemArraySerializer.deserialize(bytes);

        assertEquals(54, decoded.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], decoded[i], "case " + i);
        }
    }

    @Test
    void deserializeRejectsCorruptBytes() {
        byte[] garbage = {1, 2, 3};
        assertThrows(IOException.class, () -> ItemArraySerializer.deserialize(garbage));
    }

    @Test
    void deserializeRejectsImplausibleLength() throws IOException {
        // Longueur annoncée absurde (bien au-delà de 6 lignes) : doit être rejetée, pas allouer follement.
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(buffer)) {
            out.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(IOException.class, () -> ItemArraySerializer.deserialize(buffer.toByteArray()));
    }

    @Test
    void nullAndAirSlotsAreTreatedIdentically() throws IOException {
        ItemStack[] original = new ItemStack[2];
        original[0] = null;
        original[1] = new ItemStack(Material.AIR);

        byte[] bytes = ItemArraySerializer.serialize(original);
        ItemStack[] decoded = ItemArraySerializer.deserialize(bytes);

        assertNull(decoded[0]);
        assertNull(decoded[1]);
        assertTrue(true);
    }
}
