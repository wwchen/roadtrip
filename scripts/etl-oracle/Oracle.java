import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import kotlin.sequences.SequencesKt;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.SerializersKt;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonBuilder;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonKt;
import kotlinx.serialization.json.JsonNull;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import com.charleskorn.kaml.Yaml;
import com.charleskorn.kaml.YamlNode;
import ca.floo.roadtrip.model.metadata.Envelope;
import ca.floo.roadtrip.model.metadata.ParseResult;
import ca.floo.roadtrip.model.metadata.RequestMeta;
import ca.floo.roadtrip.model.metadata.ResponseMeta;
import ca.floo.roadtrip.model.metadata.TransformResult;
import ca.floo.roadtrip.model.metadata.registry.EtlEntry;
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry;
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry;
import ca.floo.roadtrip.service.etl.framework.InputBundle;
import ca.floo.roadtrip.service.etl.framework.SourceEtl;
import ca.floo.roadtrip.service.etl.framework.TransformCtx;

public class Oracle {
    static final Json JSON = Json.Default;
    static final Json WIRE = JsonKt.Json(Json.Default, (JsonBuilder builder) -> {
        builder.setEncodeDefaults(true);
        return Unit.INSTANCE;
    });
    static final int MAX_DEPTH = 24;
    static final String KOTLIN_LOGS_ENV = "ETL_ORACLE_KOTLIN_LOGS";
    static final String ETL_KIND = "etl";
    static final String CALL_KIND = "call";
    static final String REGISTRY_CLASS = "ca.floo.roadtrip.service.etl.framework.ProductionTerminalEtlRegistryKt";
    static final String CONSTANT_AGENCY = "constant";
    static final String DERIVED_FROM_FIELD_KEY = "derived_from_field";
    static PoiRegistry registry;

    static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static String wireOf(Enum<?> e) throws Exception {
        for (String name : new String[] {"getWire", "getId"}) {
            try { return (String) e.getClass().getMethod(name).invoke(e); } catch (NoSuchMethodException ignored) { }
        }
        return e.name().toLowerCase();
    }

    static String render(Object o, int depth) throws Exception {
        if (depth > MAX_DEPTH) throw new IllegalStateException("record nests deeper than " + MAX_DEPTH);
        if (o == null) return "null";
        if (o instanceof String) return esc((String) o);
        if (o instanceof Boolean || o instanceof Integer || o instanceof Long || o instanceof Double || o instanceof Float) return o.toString();
        if (o instanceof Enum) return esc(wireOf((Enum<?>) o));
        if (o instanceof JsonElement) return o.toString();
        if (o instanceof List) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object e : (List<?>) o) { if (!first) b.append(','); first = false; b.append(render(e, depth + 1)); }
            return b.append(']').toString();
        }
        if (o instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(','); first = false;
                b.append(esc(String.valueOf(e.getKey()))).append(':').append(render(e.getValue(), depth + 1));
            }
            return b.append('}').toString();
        }
        Class<?> k = o.getClass();
        if (k.getName().startsWith("java.")) return esc(o.toString());
        try {
            Method ser = k.getMethod("serialize");
            return esc(String.valueOf(ser.invoke(o)));
        } catch (NoSuchMethodException ignored) { }
        KSerializer<Object> wire;
        try {
            wire = SerializersKt.serializerOrNull(k);
        } catch (Throwable ignored) {
            wire = null;
        }
        if (wire != null) return WIRE.encodeToString(wire, o);
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        Method[] methods = k.getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            String n = m.getName();
            if (!(n.startsWith("get") || n.startsWith("is")) || n.equals("getClass")) continue;
            String prop = n.startsWith("get") ? n.substring(3) : n.substring(2);
            prop = Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
            if (!first) b.append(','); first = false;
            b.append(esc(prop)).append(':').append(render(m.invoke(o), depth + 1));
        }
        return b.append('}').toString();
    }

    static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e instanceof JsonPrimitive && !(e instanceof JsonNull) ? ((JsonPrimitive) e).getContent() : null;
    }

    static EtlEntry etlEntryFor(String slug) {
        for (PoiDataEntry row : registry.getPoiData()) {
            for (EtlEntry entry : row.getEtls()) if (entry.getSlug().equals(slug)) return entry;
        }
        for (var row : registry.getCampsiteData()) {
            for (EtlEntry entry : row.getEtls()) if (entry.getSlug().equals(slug)) return entry;
        }
        throw new IllegalArgumentException("no etl with slug '" + slug + "' in the POI registry");
    }

    @SuppressWarnings("unchecked")
    static SourceEtl<?, ?> etlFor(EtlEntry entry) throws Exception {
        Class<?> kt = Class.forName(REGISTRY_CLASS);
        Map<?, ?> poiAdapters = (Map<?, ?>) kt.getMethod("getPoiAdapters").invoke(null);
        Map<?, ?> campsiteAdapters = (Map<?, ?>) kt.getMethod("getCampsiteAdapters").invoke(null);
        Object spec = poiAdapters.get(entry.getAdapter());
        Object factory = spec != null ? spec.getClass().getMethod("getCreate").invoke(spec) : campsiteAdapters.get(entry.getAdapter());
        if (factory == null) throw new IllegalArgumentException("no adapter named '" + entry.getAdapter() + "'");
        Object definition = ((kotlin.jvm.functions.Function1<EtlEntry, Object>) factory).invoke(entry);
        return (SourceEtl<?, ?>) definition.getClass().getMethod("getEtl").invoke(definition);
    }

    static YamlNode agencyNodeOf(JsonElement agency) {
        if (!(agency instanceof JsonObject)) return null;
        JsonObject a = (JsonObject) agency;
        String body = CONSTANT_AGENCY.equals(text(a, "kind"))
            ? esc(text(a, "value"))
            : DERIVED_FROM_FIELD_KEY + ": " + esc(text(a, "field"));
        return Yaml.Companion.getDefault().parseToYamlNode(body);
    }

    static PoiRegistry withOverride(String slug, JsonObject recorded) {
        YamlNode agency = agencyNodeOf(recorded.get("agency"));
        String subcategory = text(recorded, "subcategory");
        List<PoiDataEntry> poiData = new ArrayList<>();
        boolean found = false;
        for (PoiDataEntry row : registry.getPoiData()) {
            EtlEntry terminal = row.getEtls().isEmpty() ? null : row.getEtls().get(row.getEtls().size() - 1);
            if (terminal != null && terminal.getSlug().equals(slug)) {
                found = true;
                poiData.add(new PoiDataEntry(row.getName(), row.getEnabled(), row.getCategory(), subcategory, agency, row.getEtls()));
            } else {
                poiData.add(row);
            }
        }
        if (!found) {
            if (subcategory != null || agency != null) {
                throw new IllegalArgumentException(
                    "no poi_data row has terminal etl '" + slug + "', so its recorded subcategory/agency cannot be applied");
            }
            return registry;
        }
        return new PoiRegistry(registry.getDataSources(), poiData, registry.getCampsiteData(), registry.getBookingProviders());
    }

    static Envelope envelopeOf(JsonObject e) {
        String url = text(e, "url");
        String part = text(e, "part");
        JsonElement payload = JSON.parseToJsonElement(text(e, "payload"));
        String fetchedAt = text(e, "fetchedAt");
        if (fetchedAt == null) throw new IllegalArgumentException("a case envelope has no fetchedAt");
        return new Envelope("f", "1", fetchedAt, new RequestMeta(url, "GET", new HashMap<>()), new ResponseMeta(200, new HashMap<>()), null, payload, part);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String runEtl(JsonObject c) throws Exception {
        String slug = text(c, "slug");
        SourceEtl etl = etlFor(etlEntryFor(slug));
        LinkedHashMap<String, List<Envelope>> captures = new LinkedHashMap<>();
        List<String> fetchedAt = new ArrayList<>();
        for (Map.Entry<String, JsonElement> in : ((JsonObject) c.get("inputs")).entrySet()) {
            List<Envelope> envelopes = new ArrayList<>();
            for (JsonElement e : (JsonArray) in.getValue()) {
                Envelope envelope = envelopeOf((JsonObject) e);
                envelopes.add(envelope);
                fetchedAt.add(esc(envelope.getFetchedAt()));
            }
            captures.put(in.getKey(), envelopes);
        }
        InputBundle bundle = new InputBundle(captures);
        TransformCtx ctx = TransformCtx.Companion.load(new File("/unused"), withOverride(slug, (JsonObject) c.get("registry")));
        List<String> out = new ArrayList<>();
        List<String> parseBad = new ArrayList<>();
        String parseOutcome = "ok";
        String transformOutcome = "ok";
        try {
            List<ParseResult> parsed = SequencesKt.toList(etl.parse(bundle));
            for (ParseResult p : parsed) {
                if (p instanceof ParseResult.Ok) {
                    try {
                        List<TransformResult> results = SequencesKt.toList(etl.transform(((ParseResult.Ok) p).getDto(), ctx));
                        for (TransformResult r : results) {
                            if (r instanceof TransformResult.Ok) out.add("{\"ok\":true,\"record\":" + render(((TransformResult.Ok) r).getRecord(), 0) + "}");
                            else {
                                TransformResult.Bad bad = (TransformResult.Bad) r;
                                out.add("{\"ok\":false,\"sourceId\":" + render(bad.getSourceId(), 0) + ",\"errors\":" + render(bad.getErrors(), 0) + "}");
                            }
                        }
                    } catch (Throwable t) {
                        transformOutcome = "throws";
                    }
                } else {
                    parseBad.add(render(((ParseResult.Bad) p).getErrors(), 0));
                }
            }
        } catch (Throwable t) {
            parseOutcome = "throws";
            transformOutcome = "skipped";
        }
        return "{\"fetchedAt\":[" + String.join(",", fetchedAt) + "],\"parse\":\"" + parseOutcome + "\",\"parseBad\":[" + String.join(",", parseBad) + "],\"transform\":\"" + transformOutcome + "\",\"results\":[" + String.join(",", out) + "]}";
    }

    static Object argOf(String type, JsonElement value) {
        String content = ((JsonPrimitive) value).getContent();
        switch (type) {
            case "json": return JSON.parseToJsonElement(content);
            case "string": return content;
            case "int": return Integer.valueOf(content);
            case "long": return Long.valueOf(content);
            case "double": return Double.valueOf(content);
            case "boolean": return Boolean.valueOf(content);
            default: throw new IllegalArgumentException("argument type " + type);
        }
    }

    static Class<?> classOf(String type, Object value) {
        switch (type) {
            case "json": return value instanceof JsonObject ? JsonObject.class : value instanceof JsonArray ? JsonArray.class : JsonElement.class;
            case "string": return String.class;
            case "int": return int.class;
            case "long": return long.class;
            case "double": return double.class;
            case "boolean": return boolean.class;
            default: throw new IllegalArgumentException("argument type " + type);
        }
    }

    static String runCall(JsonObject c) throws Exception {
        Class<?> k = Class.forName(text(c, "kotlinClass"));
        String name = text(c, "kotlinMethod");
        JsonArray types = (JsonArray) c.get("argTypes");
        JsonArray args = (JsonArray) c.get("args");
        Object[] values = new Object[args.size()];
        for (int i = 0; i < values.length; i++) values[i] = argOf(((JsonPrimitive) types.get(i)).getContent(), args.get(i));
        Method found = null;
        for (Method m : k.getDeclaredMethods()) {
            if (!m.getName().equals(name) && !m.getName().equals("access$" + name)) continue;
            if (m.getParameterCount() != values.length) continue;
            boolean fits = true;
            for (int i = 0; i < values.length; i++) {
                Class<?> p = m.getParameterTypes()[i];
                String t = ((JsonPrimitive) types.get(i)).getContent();
                if (t.equals("json") ? !p.isInstance(values[i]) : !p.equals(classOf(t, values[i]))) fits = false;
            }
            if (fits && (found == null || m.getName().startsWith("access$"))) found = m;
        }
        if (found == null) throw new IllegalArgumentException("no method " + name + " on " + k.getName());
        found.setAccessible(true);
        try {
            Object result = found.invoke(null, values);
            return "{\"threw\":false,\"value\":" + render(result, 0) + "}";
        } catch (InvocationTargetException e) {
            return "{\"threw\":true,\"value\":null}";
        }
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = System.out;
        System.setOut(System.getenv(KOTLIN_LOGS_ENV) == null ? new PrintStream(OutputStream.nullOutputStream()) : System.err);
        registry = PoiRegistry.Companion.load(new File(args[1]));
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.isBlank()) continue;
            JsonObject c = (JsonObject) JSON.parseToJsonElement(line);
            String kind = text(c, "kind");
            protocol.println(ETL_KIND.equals(kind) ? runEtl(c) : runCall(c));
        }
    }
}
