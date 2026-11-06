import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import kotlin.text.StringsKt;
import kotlinx.serialization.internal.IntSerializer;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonNull;
import kotlinx.serialization.json.JsonPrimitive;

public class NumberProbe {
    private static final String PARSE_MODE = "parse";
    private static final String POW_MODE = "pow";
    private static final String STDLIB_MODE = "stdlib";
    private static final String RINT_MODE = "rint";
    private static final String IGNORE_CASE_MODE = "ignorecase";
    private static final String CASE_MODE = "case";
    private static final String CASE_STRING_MODE = "casestrings";
    private static final String SIGMA_MODE = "sigma";
    private static final String CAPITAL_SIGMA = "\u03a3";
    private static final int CODE_UNITS = 0x10000;
    private static final String FIELD_SEPARATOR = "\t";
    private static final String THROWS = "THROWS";
    private static final String NOT_A_NUMBER_TOKEN = "SKIP";
    private static final int[] POW_EXPONENTS = exponents();

    private static int[] exponents() {
        int[] wide = {-100000, -1000, 1000, 100000};
        int[] all = new int[801 + wide.length];
        for (int k = -400; k <= 400; k++) all[k + 400] = k;
        System.arraycopy(wide, 0, all, 801, wide.length);
        return all;
    }

    private static String read(String literal) {
        String intValue = "ERR";
        String longValue = "ERR";
        String strict;
        String getter = THROWS;
        String unquotedGetter = NOT_A_NUMBER_TOKEN;
        try {
            JsonPrimitive primitive = (JsonPrimitive) Json.Default.parseToJsonElement("\"" + literal + "\"");
            intValue = String.valueOf(JsonElementKt.getIntOrNull(primitive));
            longValue = String.valueOf(JsonElementKt.getLongOrNull(primitive));
            try {
                getter = String.valueOf(JsonElementKt.getLong(primitive));
            } catch (Throwable thrown) {
                getter = THROWS;
            }
        } catch (Throwable ignored) {
        }
        try {
            JsonElement token = Json.Default.parseToJsonElement(literal);
            if (token instanceof JsonPrimitive && !((JsonPrimitive) token).isString() && !(token instanceof JsonNull)) {
                try {
                    unquotedGetter = String.valueOf(JsonElementKt.getLong((JsonPrimitive) token));
                } catch (Throwable thrown) {
                    unquotedGetter = THROWS;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            strict = String.valueOf(Json.Default.decodeFromString(IntSerializer.INSTANCE, literal));
        } catch (Throwable thrown) {
            strict = THROWS;
        }
        return literal + FIELD_SEPARATOR + intValue + FIELD_SEPARATOR + longValue + FIELD_SEPARATOR + strict
            + FIELD_SEPARATOR + getter + FIELD_SEPARATOR + unquotedGetter;
    }

    private static String stdlib(String literal) {
        return literal + FIELD_SEPARATOR + StringsKt.toIntOrNull(literal) + FIELD_SEPARATOR + StringsKt.toLongOrNull(literal)
            + FIELD_SEPARATOR + StringsKt.toBooleanStrictOrNull(literal);
    }

    private static String codesOf(String text) {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) codes.append(',');
            codes.append((int) text.charAt(i));
        }
        return codes.toString();
    }

    private static void caseMapping(StringBuilder out) {
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            String candidate = codePoint < CODE_UNITS
                ? String.valueOf((char) codePoint)
                : new String(Character.toChars(codePoint));
            out.append(codePoint).append(FIELD_SEPARATOR).append(codesOf(candidate.toLowerCase(Locale.ROOT)))
                .append(FIELD_SEPARATOR).append(codesOf(candidate.toUpperCase(Locale.ROOT))).append('\n');
        }
    }

    private static String caseString(String literal) {
        return literal + FIELD_SEPARATOR + codesOf(literal.toLowerCase(Locale.ROOT)) + FIELD_SEPARATOR + codesOf(literal.toUpperCase(Locale.ROOT));
    }

    private static void sigmaContexts(StringBuilder out) {
        for (int unit = 0; unit < CODE_UNITS; unit++) {
            String context = CAPITAL_SIGMA + (char) unit + CAPITAL_SIGMA;
            out.append(unit).append(FIELD_SEPARATOR).append(codesOf(context.toLowerCase(Locale.ROOT))).append('\n');
        }
    }

    private static String rint(String literal) {
        return literal + FIELD_SEPARATOR + Double.doubleToLongBits(Math.rint(Double.parseDouble(literal)));
    }

    private static void ignoreCase(StringBuilder out) {
        for (int unit = 0; unit < CODE_UNITS; unit++) {
            String candidate = String.valueOf((char) unit);
            for (char letter = 'a'; letter <= 'z'; letter++) {
                if (StringsKt.equals(candidate, String.valueOf(letter), true)) out.append(unit).append(FIELD_SEPARATOR).append(letter).append('\n');
            }
        }
    }

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        if (POW_MODE.equals(args[0])) {
            for (int k : POW_EXPONENTS) {
                out.append(k).append(FIELD_SEPARATOR).append(Double.doubleToLongBits(Math.pow(10, k)))
                    .append(FIELD_SEPARATOR).append(Double.toString(Math.pow(10, k))).append('\n');
            }
        } else if (PARSE_MODE.equals(args[0])) {
            for (String literal : Files.readAllLines(Paths.get(args[1]))) out.append(read(literal)).append('\n');
        } else if (STDLIB_MODE.equals(args[0])) {
            for (String literal : Files.readAllLines(Paths.get(args[1]))) out.append(stdlib(literal)).append('\n');
        } else if (RINT_MODE.equals(args[0])) {
            for (String literal : Files.readAllLines(Paths.get(args[1]))) out.append(rint(literal)).append('\n');
        } else if (IGNORE_CASE_MODE.equals(args[0])) {
            ignoreCase(out);
        } else if (CASE_MODE.equals(args[0])) {
            caseMapping(out);
        } else if (SIGMA_MODE.equals(args[0])) {
            sigmaContexts(out);
        } else if (CASE_STRING_MODE.equals(args[0])) {
            for (String literal : Files.readAllLines(Paths.get(args[1]))) out.append(caseString(literal)).append('\n');
        } else {
            throw new IllegalArgumentException(args[0]);
        }
        System.out.print(out);
    }
}
