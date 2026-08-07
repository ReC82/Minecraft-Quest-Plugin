package be.lloyd.rpgquest.config;

import java.util.List;

public record DialogueConfig(RendererKind renderer, List<String> allowedCommands) {

    public DialogueConfig {
        allowedCommands = List.copyOf(allowedCommands);
    }
}
