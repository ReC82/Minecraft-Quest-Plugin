package be.lloyd.rpgquest.item.model;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * Un modificateur d'attribut à appliquer à l'objet. L'identifiant du
 * modificateur Bukkit ({@link AttributeModifier}) n'est pas porté par le
 * YAML : {@link be.lloyd.rpgquest.item.YamlCustomItemRegistry} le dérive de
 * l'id de l'objet et de l'attribut à la création, pour garantir son unicité
 * sans alourdir la définition.
 */
public record ItemAttributeSpec(Attribute attribute, double amount, AttributeModifier.Operation operation, EquipmentSlotGroup slot) {

    public ItemAttributeSpec {
        if (attribute == null) {
            throw new IllegalArgumentException("attribute ne peut pas être nul.");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation ne peut pas être nulle.");
        }
        if (slot == null) {
            throw new IllegalArgumentException("slot ne peut pas être nul.");
        }
    }
}
