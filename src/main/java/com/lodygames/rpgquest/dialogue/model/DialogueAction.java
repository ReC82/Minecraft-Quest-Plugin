package com.lodygames.rpgquest.dialogue.model;

public sealed interface DialogueAction
        permits StartQuestAction, AdvanceQuestAction, TurnInQuestAction, GiveItemAction, TakeItemAction,
                SetVariableAction, RunSafeCommandAction, OpenDialogueAction, OpenMerchantAction, GiveStarterKitAction,
                DeliverQuestItemsAction, CloseAction {

    ActionType type();
}
