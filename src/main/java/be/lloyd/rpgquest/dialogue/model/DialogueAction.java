package be.lloyd.rpgquest.dialogue.model;

public sealed interface DialogueAction
        permits StartQuestAction, AdvanceQuestAction, TurnInQuestAction, GiveItemAction, TakeItemAction,
                SetVariableAction, RunSafeCommandAction, OpenDialogueAction, CloseAction {

    ActionType type();
}
