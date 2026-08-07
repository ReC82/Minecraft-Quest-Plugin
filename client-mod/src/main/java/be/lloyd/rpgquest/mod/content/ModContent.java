package be.lloyd.rpgquest.mod.content;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Un vrai bloc et un objet associé (mission étape 23, point 5) —
 * démonstration du contournement d'outillage, pas un contenu livré par le
 * serveur : un serveur Paper vanilla-compatible (sans NMS, voir
 * PROJECT_RULES.md) ne peut pas synchroniser un nouvel identifiant de
 * bloc/objet vers les clients. Ce contenu n'existe donc que dans l'onglet
 * créatif du mod lui-même, jamais posé/donné par le serveur — voir
 * docs/CLIENT_MOD.md pour cette limite assumée.
 */
public final class ModContent {

    public static final Block CRYSTAL_DISPLAY_BLOCK = new Block(
            AbstractBlock.Settings.create().mapColor(MapColor.LIGHT_BLUE).strength(1.5f).requiresTool());

    public static final Item CRYSTAL_DISPLAY_ITEM = new BlockItem(CRYSTAL_DISPLAY_BLOCK, new Item.Settings());

    private ModContent() {
    }

    public static void register() {
        Registry.register(Registries.BLOCK, Identifier.of("rpgquest", "crystal_display"), CRYSTAL_DISPLAY_BLOCK);
        Registry.register(Registries.ITEM, Identifier.of("rpgquest", "crystal_display"), CRYSTAL_DISPLAY_ITEM);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> entries.add(CRYSTAL_DISPLAY_ITEM));
    }
}
