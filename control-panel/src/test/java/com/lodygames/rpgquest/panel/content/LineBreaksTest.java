package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #728 — retours à la ligne d'une réplique. */
class LineBreaksTest {

    @Test
    void browserLineBreaksBecomeOneNewline() {
        assertEquals("a\nb\nc\nd", LineBreaks.normalize("a\r\nb\rc\nd"));
        assertNull(LineBreaks.normalize(null));
        String untouched = "<gold>Salut !<br>Ça va ?</gold>";
        assertSame(untouched, LineBreaks.normalize(untouched), "texte sans retour chariot rendu tel quel");
    }

    @Test
    void literalLookalikesAreFoundButRealBreaksAreNot() {
        assertEquals(List.of("</br>", "\\n"), LineBreaks.literalLookalikes("a</BR>b\\nc"));
        assertEquals(List.of(), LineBreaks.literalLookalikes("a<br>b<newline>c\nd"));
        assertEquals(List.of(), LineBreaks.literalLookalikes(null));
    }
}
