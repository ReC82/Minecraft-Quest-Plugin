package com.lodygames.rpgquest.database.migration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Empreinte logique d'une table, <strong>indépendante du moteur SQL</strong> (issue #42).
 *
 * <p>Chaque ligne est réduite à une forme canonique — nombres en décimal normalisé (SQLite
 * {@code INTEGER} et MariaDB {@code BIGINT}, {@code 1} et {@code 1.0} donnent la même chose),
 * texte en UTF-8, binaire tel quel, {@code NULL} distinct de la chaîne vide — puis hachée en
 * SHA-256. Les empreintes de lignes sont <strong>additionnées modulo 2²⁵⁶</strong> : le résultat
 * ne dépend pas de l'ordre de lecture (les deux moteurs ne trient pas forcément le texte de la même
 * façon) et compte les doublons (contrairement à un XOR).</p>
 *
 * <p>Deux tables ont la même empreinte et le même nombre de lignes si et seulement si elles
 * contiennent le même multi-ensemble de lignes, aux collisions SHA-256 près.</p>
 */
public final class LogicalChecksum {

    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);

    private LogicalChecksum() {
    }

    /** @param rows nombre de lignes ; {@code checksum} : empreinte hexadécimale sur 64 caractères */
    public record Result(long rows, String checksum) {
    }

    /** Calcule l'empreinte de {@code columns} de {@code table} (colonnes dans un ordre fixe). */
    public static Result of(Connection connection, String table, List<String> columns) throws SQLException {
        SqliteSource.requireIdentifier(table);
        columns.forEach(SqliteSource::requireIdentifier);
        BigInteger sum = BigInteger.ZERO;
        long rows = 0;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT " + String.join(", ", columns) + " FROM " + table)) {
            while (resultSet.next()) {
                MessageDigest digest = sha256();
                for (int i = 1; i <= columns.size(); i++) {
                    feed(digest, materialise(resultSet.getObject(i)));
                }
                sum = sum.add(new BigInteger(1, digest.digest())).mod(MODULUS);
                rows++;
            }
        }
        return new Result(rows, String.format("%064x", sum));
    }

    /**
     * Le pilote MariaDB renvoie un {@link Blob} pour {@code LONGBLOB} là où SQLite renvoie un
     * {@code byte[]} : on compare le contenu, jamais l'objet du pilote.
     */
    static Object materialise(Object value) throws SQLException {
        if (value instanceof Blob blob) {
            long length = blob.length();
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("BLOB trop volumineux pour l'empreinte : " + length + " octets");
            }
            return blob.getBytes(1, (int) length);
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, (int) Math.min(Integer.MAX_VALUE, clob.length()));
        }
        return value;
    }

    /** Forme canonique d'une valeur, préfixée de son genre et de sa longueur (sans ambiguïté). */
    static void feed(MessageDigest digest, Object value) {
        byte tag;
        byte[] bytes;
        if (value == null) {
            tag = 'N';
            bytes = new byte[0];
        } else if (value instanceof byte[] raw) {
            tag = 'B';
            bytes = raw;
        } else if (value instanceof Number number) {
            tag = 'D';
            bytes = canonicalNumber(number).getBytes(StandardCharsets.US_ASCII);
        } else if (value instanceof Boolean bool) {
            tag = 'D';
            bytes = (bool ? "1" : "0").getBytes(StandardCharsets.US_ASCII);
        } else {
            tag = 'T';
            bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        }
        digest.update(tag);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    static String canonicalNumber(Number number) {
        if ((number instanceof Double || number instanceof Float) && !Double.isFinite(number.doubleValue())) {
            return Double.toString(number.doubleValue());
        }
        BigDecimal decimal = switch (number) {
            case BigDecimal big -> big;
            case BigInteger big -> new BigDecimal(big);
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f.doubleValue());
            default -> BigDecimal.valueOf(number.longValue());
        };
        if (decimal.signum() == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
