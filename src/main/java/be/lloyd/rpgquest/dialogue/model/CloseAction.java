package be.lloyd.rpgquest.dialogue.model;

public record CloseAction() implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.CLOSE;
    }
}
