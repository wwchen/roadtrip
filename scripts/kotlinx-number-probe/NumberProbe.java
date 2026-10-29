import java.nio.file.Files;
import java.nio.file.Paths;
import kotlinx.serialization.internal.IntSerializer;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonPrimitive;

public class NumberProbe {
    private static final String PARSE_MODE = "parse";
    private static final String POW_MODE = "pow";
    private static final String FIELD_SEPARATOR = "\t";
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
        try {
            JsonPrimitive primitive = (JsonPrimitive) Json.Default.parseToJsonElement("\"" + literal + "\"");
            intValue = String.valueOf(JsonElementKt.getIntOrNull(primitive));
            longValue = String.valueOf(JsonElementKt.getLongOrNull(primitive));
        } catch (Throwable ignored) {
        }
        try {
            strict = String.valueOf(Json.Default.decodeFromString(IntSerializer.INSTANCE, literal));
        } catch (Throwable thrown) {
            strict = "THROWS";
        }
        return literal + FIELD_SEPARATOR + intValue + FIELD_SEPARATOR + longValue + FIELD_SEPARATOR + strict;
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
        } else {
            throw new IllegalArgumentException(args[0]);
        }
        System.out.print(out);
    }
}
