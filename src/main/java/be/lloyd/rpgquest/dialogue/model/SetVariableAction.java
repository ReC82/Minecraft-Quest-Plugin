package be.lloyd.rpgquest.dialogue.model;

public record SetVariableAction(String key, String value) implements DialogueAction {

    public SetVariableAction {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key ne peut pas être vide.");
        }
        if (value == null) {
            throw new IllegalArgumentException("value ne peut pas être nul.");
        }
    }

    @Override
    public ActionType type() {
        return ActionType.SET_VARIABLE;
    }
}
