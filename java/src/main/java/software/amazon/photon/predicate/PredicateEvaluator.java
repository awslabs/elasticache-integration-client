package software.amazon.photon.predicate;

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
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.RequestMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Compiles and evaluates the CEL routing predicates returned by {@code photon.config}.
 *
 * <p>Compilation and evaluation are fail-safe: a predicate that cannot be compiled
 * is ignored, and an evaluation failure counts as no match.
 */
public final class PredicateEvaluator {

    private static final Logger LOGGER = Logger.getLogger(PredicateEvaluator.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};
    private static final Map<String, CelType> CONTEXT_VARIABLE_TYPES =
            contextVariableTypes();
    private static final Object INVALID_JSON_VALUE = new Object();
    private static final CelRuntime CEL_RUNTIME =
            CelRuntimeFactory.plannerRuntimeBuilder().build();

    private final List<CompiledPredicate> predicates;

    private PredicateEvaluator(List<CompiledPredicate> predicates) {
        this.predicates = Collections.unmodifiableList(new ArrayList<>(predicates));
    }

    /**
     * Compile the ordered predicate entries in a resolved config.
     *
     * <p>Missing or malformed predicate collections compile to an empty evaluator.
     * Individual entries without a string {@code expr}, or whose expression cannot
     * be compiled, are ignored without affecting valid siblings.
     */
    public static PredicateEvaluator compile(Map<String, Object> rawConfig) {
        if (rawConfig == null || !(rawConfig.get("predicates") instanceof List)) {
            return new PredicateEvaluator(Collections.<CompiledPredicate>emptyList());
        }

        List<CompiledPredicate> compiled = new ArrayList<>();
        for (Object rawEntry : (List<?>) rawConfig.get("predicates")) {
            if (!(rawEntry instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) rawEntry;
            Object expression = entry.get("expr");
            if (!(expression instanceof String)) {
                continue;
            }
            Map<String, Object> variables;
            if (entry.get("vars") instanceof Map) {
                variables = stringKeyedMap(entry.get("vars"));
                if (variables == null) {
                    LOGGER.warning("Ignoring predicate with a non-string variable key");
                    continue;
                }
            } else {
                variables = Collections.emptyMap();
            }
            try {
                compiled.add(compileOne((String) expression, variables));
            } catch (CelValidationException | CelEvaluationException | RuntimeException e) {
                LOGGER.log(Level.WARNING,
                        "Ignoring predicate with an uncompilable CEL expression", e);
            }
        }
        return new PredicateEvaluator(compiled);
    }

    /**
     * Return true when the first compiled predicate matches the request.
     */
    public boolean route(PhotonRequest request) {
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

    private static CompiledPredicate compileOne(
            String expression, Map<String, Object> variables)
            throws CelValidationException, CelEvaluationException {
        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        for (Map.Entry<String, CelType> contextVariable : CONTEXT_VARIABLE_TYPES.entrySet()) {
            CelType type = variables.containsKey(contextVariable.getKey())
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

    private static Map<String, Object> buildContext(PhotonRequest request) {
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
