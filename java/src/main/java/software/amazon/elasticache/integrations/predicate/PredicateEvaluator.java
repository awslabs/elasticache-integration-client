package software.amazon.elasticache.integrations.predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigUnevaluable;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.RequestMetadata;

/**
 * Compiles and evaluates the CEL routing predicates returned by {@code aws.config}.
 *
 * <p>An evaluation failure, or a body that is not a JSON object, counts as no match. An entry that
 * cannot be compiled is different: it taints the whole config, which is rejected rather than
 * caching by the entries that did compile.
 */
public final class PredicateEvaluator {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};
    private static final Map<String, CelType> CONTEXT_VARIABLE_TYPES = contextVariableTypes();
    private static final Object INVALID_JSON_VALUE = new Object();
    private static final CelRuntime CEL_RUNTIME = CelRuntimeFactory.plannerRuntimeBuilder().build();

    private final List<CompiledPredicate> predicates;

    private PredicateEvaluator(List<CompiledPredicate> predicates) {
        this.predicates = Collections.unmodifiableList(new ArrayList<>(predicates));
    }

    /**
     * Compile the {@code predicates} of a resolved config into evaluatable programs, in wire order.
     *
     * <p>A missing {@code predicates} key compiles to an empty evaluator, so every request passes
     * through.
     *
     * @param rawConfig the parsed config document, or {@code null}
     * @return an evaluator for the config's predicates, in config order
     * @throws ElastiCacheIntegrationsConfigUnevaluable {@code predicates} is present but not a list,
     *     an entry is not an object, has no string {@code expr}, or has an expression this client
     *     cannot compile. Caching by the entries that happened to compile would route a different set
     *     of requests than the proxy resolved.
     */
    public static PredicateEvaluator compile(Map<String, Object> rawConfig) {
        if (rawConfig == null || !rawConfig.containsKey("predicates")) {
            return new PredicateEvaluator(Collections.<CompiledPredicate>emptyList());
        }
        Object entries = rawConfig.get("predicates");
        if (!(entries instanceof List)) {
            throw new ElastiCacheIntegrationsConfigUnevaluable(
                    "Config 'predicates' must be a list, got "
                            + (entries == null ? "null" : entries.getClass().getSimpleName()));
        }

        List<CompiledPredicate> compiled = new ArrayList<>();
        for (Object rawEntry : (List<?>) entries) {
            compiled.add(compilePredicate(rawEntry));
        }
        return new PredicateEvaluator(compiled);
    }

    /**
     * Return true when the request should go to the cache. The first match wins, and an empty list
     * routes nothing.
     *
     * <p>Builds the request context once and reuses it across predicates.
     *
     * @param request the request to evaluate
     * @return true to send the request through Cache, false to send it to the origin
     */
    public boolean shouldRouteToCache(ElastiCacheIntegrationsRequest request) {
        if (predicates.isEmpty()) {
            return false;
        }
        Map<String, Object> context = buildContext(request);
        if (context == null) {
            return false;
        }
        for (CompiledPredicate predicate : predicates) {
            if (predicate.matches(context)) {
                return true;
            }
        }
        return false;
    }

    int size() {
        return predicates.size();
    }

    private static CompiledPredicate compilePredicate(Object rawEntry) {
        if (!(rawEntry instanceof Map)) {
            throw new ElastiCacheIntegrationsConfigUnevaluable("Predicate entry is not an object");
        }
        Map<?, ?> entry = (Map<?, ?>) rawEntry;
        Object expression = entry.get("expr");
        if (!(expression instanceof String)) {
            throw new ElastiCacheIntegrationsConfigUnevaluable("Predicate entry has no string 'expr'");
        }
        Map<String, Object> variables = stringKeyedMap(entry.get("vars"));
        if (variables == null) {
            throw new ElastiCacheIntegrationsConfigUnevaluable(
                    "Predicate entry has a non-string variable key");
        }
        try {
            return compileOne((String) expression, variables);
        } catch (CelValidationException | CelEvaluationException | RuntimeException e) {
            throw new ElastiCacheIntegrationsConfigUnevaluable(
                    "Predicate expression cannot be compiled: \"" + expression + "\"", e);
        }
    }

    private static CompiledPredicate compileOne(String expression, Map<String, Object> variables)
            throws CelValidationException, CelEvaluationException {
        CelCompilerBuilder builder =
                CelCompilerFactory.standardCelCompilerBuilder()
                        .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        for (Map.Entry<String, CelType> contextVariable : CONTEXT_VARIABLE_TYPES.entrySet()) {
            CelType type =
                    variables.containsKey(contextVariable.getKey())
                            ? SimpleType.DYN
                            : contextVariable.getValue();
            builder.addVar(contextVariable.getKey(), type);
        }
        for (String name : variables.keySet()) {
            if (!CONTEXT_VARIABLE_TYPES.containsKey(name)) {
                builder.addVar(name, SimpleType.DYN);
            }
        }
        CelCompiler compiler = builder.build();
        CelAbstractSyntaxTree ast = compiler.compile(expression).getAst();
        CelRuntime.Program program = CEL_RUNTIME.createProgram(ast);
        return new CompiledPredicate(program, variables);
    }

    private static Map<String, Object> buildContext(ElastiCacheIntegrationsRequest request) {
        RequestMetadata metadata = request.getMetadata();
        Map<String, Object> body = parseBody(request.getBody());
        if (body == null) {
            return null;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("service", metadata.getService());
        context.put("operation", metadata.getOperation());
        context.put("method", request.getMethod());
        context.put("url", request.getUrl());
        context.put("headers", new LinkedHashMap<>(request.getHeaders()));
        context.put("body", body);
        return context;
    }

    private static Map<String, Object> parseBody(byte[] body) {
        if (body == null || body.length == 0) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(body, MAP_TYPE);
            return parsed;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> stringKeyedMap(Object value) {
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                return null;
            }
            Object copiedValue = immutableJsonValue(entry.getValue());
            if (copiedValue == INVALID_JSON_VALUE) {
                return null;
            }
            copy.put((String) entry.getKey(), copiedValue);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = stringKeyedMap(value);
            return copy != null ? copy : INVALID_JSON_VALUE;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                Object copiedItem = immutableJsonValue(item);
                if (copiedItem == INVALID_JSON_VALUE) {
                    return INVALID_JSON_VALUE;
                }
                copy.add(copiedItem);
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static Map<String, CelType> contextVariableTypes() {
        Map<String, CelType> types = new LinkedHashMap<>();
        types.put("service", SimpleType.STRING);
        types.put("operation", SimpleType.STRING);
        types.put("method", SimpleType.STRING);
        types.put("url", SimpleType.STRING);
        types.put("headers", MapType.create(SimpleType.STRING, SimpleType.STRING));
        types.put("body", SimpleType.DYN);
        return Collections.unmodifiableMap(types);
    }

    private static final class CompiledPredicate {
        private final CelRuntime.Program program;
        private final Map<String, Object> variables;

        private CompiledPredicate(CelRuntime.Program program, Map<String, Object> variables) {
            this.program = program;
            this.variables = variables;
        }

        private boolean matches(Map<String, Object> context) {
            Map<String, Object> activation = new LinkedHashMap<>(context);
            activation.putAll(variables);
            try {
                return Boolean.TRUE.equals(program.eval(activation));
            } catch (CelEvaluationException | RuntimeException e) {
                return false;
            }
        }
    }
}
