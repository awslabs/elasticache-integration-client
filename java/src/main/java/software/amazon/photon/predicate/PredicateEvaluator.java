package software.amazon.photon.predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Compiles and evaluates the CEL routing predicates returned by {@code photon.config}.
 *
 * <p>Compilation and evaluation are fail-safe: a predicate that cannot be compiled
 * is ignored, and an evaluation failure counts as no match.
 */
public final class PredicateEvaluator {

    static final int MAX_BODY_INSPECT_BYTES = 512 * 1024;

    private static final Logger LOGGER = Logger.getLogger(PredicateEvaluator.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};
    private static final Set<String> CONTEXT_VARIABLES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "service", "operation", "method", "url", "headers", "body")));
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
            Map<String, Object> variables = stringKeyedMap(entry.get("vars"));
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
        Set<String> names = new LinkedHashSet<>(CONTEXT_VARIABLES);
        names.addAll(variables.keySet());

        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        for (String name : names) {
            builder.addVar(name, SimpleType.DYN);
        }
        CelCompiler compiler = builder.build();
        CelAbstractSyntaxTree ast = compiler.compile(expression).getAst();
        CelRuntime.Program program = CEL_RUNTIME.createProgram(ast);
        return new CompiledPredicate(program, variables);
    }

    private static Map<String, Object> buildContext(PhotonRequest request) {
        RequestMetadata metadata = request.getMetadata();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("service", metadata.getService());
        context.put("operation", metadata.getOperation());
        context.put("method", request.getMethod());
        context.put("url", request.getUrl());
        context.put("headers", new LinkedHashMap<>(request.getHeaders()));
        context.put("body", parseBody(request.getBody()));
        return context;
    }

    private static Map<String, Object> parseBody(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_INSPECT_BYTES) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(body, MAP_TYPE);
            return parsed != null ? parsed : Collections.<String, Object>emptyMap();
        } catch (IOException | RuntimeException e) {
            return Collections.emptyMap();
        }
    }

    private static Map<String, Object> stringKeyedMap(Object value) {
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String) {
                copy.put((String) entry.getKey(), immutableJsonValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof Map) {
            return stringKeyedMap(value);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(immutableJsonValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
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
