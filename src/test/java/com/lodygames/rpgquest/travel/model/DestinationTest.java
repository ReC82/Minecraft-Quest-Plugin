package com.lodygames.rpgquest.travel.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DestinationTest {

    @Test
    void blankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Destination("", "world", 0, 64, 0, 0, 0));
    }

    @Test
    void invalidIdCharactersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Destination("Village Center", "world", 0, 64, 0, 0, 0));
    }

    @Test
    void blankWorldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Destination("village", "", 0, 64, 0, 0, 0));
    }
}
