package com.lodygames.rpgquest.panel.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Issue #283 — un envoi de fichier tel qu'un navigateur le produit, lu octet pour octet. */
class MultipartTest {

    static byte[] body(String boundary, byte[] file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\ntok\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\nprojé\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"façade.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    @Test
    void fieldsAndABinaryFileAreReadExactly() throws Exception {
        byte[] file = new byte[256];
        for (int i = 0; i < file.length; i++) {
            file[i] = (byte) i;   // tous les octets, y compris \r, \n et les non-UTF-8
        }
        var form = Multipart.parse("multipart/form-data; boundary=----X42", body("----X42", file)).orElseThrow();
        assertEquals("tok", form.fields().get("_csrf"));
        assertEquals("projé", form.fields().get("id"));
        var part = form.file("image").orElseThrow();
        assertEquals("façade.png", part.fileName());
        assertArrayEquals(file, part.data());
    }

    @Test
    void anythingElseIsRefusedNotGuessed() throws Exception {
        assertTrue(Multipart.parse("application/x-www-form-urlencoded", new byte[0]).isEmpty());
        assertTrue(Multipart.parse("multipart/form-data", new byte[0]).isEmpty(), "sans frontière");
        assertTrue(Multipart.parse("multipart/form-data; boundary=abc", "pas de frontière".getBytes()).isEmpty());
    }
}
