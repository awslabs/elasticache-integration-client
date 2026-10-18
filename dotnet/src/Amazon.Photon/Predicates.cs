using System.Text.Json;
using System.Text.Unicode;
using Antlr4.Runtime.Tree;
using Cel;
using Cel.Internal;

namespace Amazon.Photon;

/// <summary>
/// The CEL routing predicates of a resolved config, parsed once. Immutable, so it is safe to
/// share across threads; a compiled program keeps no state between calls.
/// </summary>
internal sealed class PredicateEvaluator
{
    /// <summary>Routes nothing.</summary>
    public static readonly PredicateEvaluator Empty = new([]);

    // DynamoDB nests attribute values up to 32 levels, about 70 JSON levels in a request body.
    private static readonly JsonDocumentOptions BodyOptions = new() { MaxDepth = 256 };

    // Predicates that read the body come last, so a body-free match never parses it.
    private readonly CompiledPredicate[] _predicates;

    private PredicateEvaluator(CompiledPredicate[] predicates) => _predicates = predicates;

    /// <summary>Compiles the predicates of a resolved config. An empty array routes nothing.</summary>
    /// <exception cref="PhotonProtocolException">A format major other than 1, no <c>predicates</c> array, or an entry that does not compile.</exception>
    public static PredicateEvaluator Compile(ResolvedConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        if (config.Version is { } version && version.Split('.')[0] != "1")
        {
            throw Unusable($"config format {version} is not supported; this client reads 1.x");
        }

        return config.Raw.TryGetProperty("predicates", out var predicates) ? Compile(predicates) : throw Unusable("it has no predicates");
    }

    /// <summary>Compiles a <c>predicates</c> array.</summary>
    /// <exception cref="PhotonProtocolException">Not an array, or an entry that does not compile.</exception>
    public static PredicateEvaluator Compile(JsonElement predicates)
    {
        if (predicates.ValueKind != JsonValueKind.Array)
        {
            throw Unusable($"predicates is {predicates.ValueKind}, not an array");
        }

        var environment = new CelEnvironment(null, null);
        var compiled = new List<CompiledPredicate>();
        var index = 0;
        foreach (var entry in predicates.EnumerateArray())
        {
            compiled.Add(TryCompile(environment, entry, out var reason, out var error)
                ?? throw Unusable($"predicate {index} has {reason}", error));
            index++;
        }

        return new([.. compiled.OrderBy(p => p.ReadsBody)]);
    }

    private static PhotonProtocolException Unusable(string detail, Exception? cause = null) =>
        new($"Unusable {PhotonProtocol.ConfigCommand} reply: {detail}.", cause);

    /// <summary>True when any predicate is exactly <c>true</c> for the request.</summary>
    public bool ShouldRoute(PhotonRequest request)
    {
        if (_predicates.Length == 0)
        {
            return false;
        }

        using var view = new PredicateRequest(request);
        foreach (var predicate in _predicates)
        {
            // A body that is not a JSON object matches no predicate that reads it, and only those are left.
            if (predicate.ReadsBody && !view.TryLoadBody())
            {
                return false;
            }

            if (predicate.Matches(view))
            {
                return true;
            }
        }

        return false;
    }

    private static CompiledPredicate? TryCompile(CelEnvironment environment, JsonElement entry, out string reason, out Exception? error)
    {
        error = null;
        if (entry.ValueKind != JsonValueKind.Object)
        {
            reason = "a value that is not an object";
            return null;
        }

        if (!entry.TryGetProperty("expr", out var expr) || expr.ValueKind != JsonValueKind.String)
        {
            reason = "no string expr";
            return null;
        }

        var hasVars = entry.TryGetProperty("vars", out var vars);
        if (hasVars && vars.ValueKind != JsonValueKind.Object)
        {
            reason = "vars that is not an object";
            return null;
        }

        try
        {
            var variables = hasVars ? ToMap(vars) : [];
            var tree = environment.Parse(expr.GetString()!);
            reason = "";
            return new CompiledPredicate(
                environment.Compile(tree), FastPredicate.TryCompile(environment, tree, variables), variables, ReadsBody(tree, variables));
        }
        catch (Exception e)
        {
            reason = "an invalid expression or vars";
            error = e;
            return null;
        }
    }

    /// <summary>
    /// Whether the expression can read the request body: it names <c>body</c>, and no variable of
    /// that name hides it. A macro variable named <c>body</c>, or a <c>vars</c> key such as
    /// <c>body.TableName</c>, also counts.
    /// </summary>
    private static bool ReadsBody(IParseTree tree, IReadOnlyDictionary<string, object?> variables) =>
        !variables.ContainsKey("body") && Names(tree, "body");

    private static bool Names(IParseTree tree, string name)
    {
        if (tree is CelParser.IdentOrGlobalCallContext { id.Text: var id } && id == name)
        {
            return true;
        }

        for (var i = 0; i < tree.ChildCount; i++)
        {
            if (Names(tree.GetChild(i), name))
            {
                return true;
            }
        }

        return false;
    }

    /// <summary>
    /// Parses the body. An empty body is <c>{}</c>, returned as a null document. False when the body
    /// is not a JSON object, or holds a string that is not valid UTF-8 or UTF-16.
    /// </summary>
    internal static bool TryParseBody(ReadOnlyMemory<byte> body, out JsonDocument? document)
    {
        document = null;
        if (body.IsEmpty)
        {
            return true;
        }

        if (body.Span.StartsWith("\uFEFF"u8))
        {
            body = body[3..];
        }

        if (!Utf8.IsValid(body.Span))
        {
            return false;
        }

        var parsed = false;
        try
        {
            document = JsonDocument.Parse(body, BodyOptions);
            if (document.RootElement.ValueKind == JsonValueKind.Object)
            {
                // Only an escaped surrogate can still fail to decode; converting reads every string.
                if (HasSurrogateEscape(body.Span))
                {
                    ToMap(document.RootElement);
                }

                parsed = true;
            }
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException)
        {
            // Malformed JSON, or an unpaired surrogate escape.
        }
        finally
        {
            if (!parsed)
            {
                document?.Dispose();
                document = null;
            }
        }

        return parsed;
    }

    private static bool HasSurrogateEscape(ReadOnlySpan<byte> json)
    {
        for (var i = json.IndexOf("\\u"u8); i >= 0 && i + 3 < json.Length; i = json.IndexOf("\\u"u8))
        {
            if ((json[i + 2] | 0x20) == 'd' && "89abcdefABCDEF"u8.Contains(json[i + 3]))
            {
                return true;
            }

            json = json[(i + 2)..];
        }

        return false;
    }

    internal static Dictionary<string, object?> ToMap(JsonElement value)
    {
        // A repeated name keeps its last value, as JSON.parse does.
        var map = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var property in value.EnumerateObject())
        {
            map[property.Name] = ToCel(property.Value);
        }

        return map;
    }

    private static object? ToCel(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Object => ToMap(value),
        JsonValueKind.Array => value.EnumerateArray().Select(ToCel).ToList(),
        JsonValueKind.String => value.GetString(),
        JsonValueKind.Number => value.TryGetInt64(out var integer) ? integer : value.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => null,
    };

    private sealed class CompiledPredicate(CelProgramDelegate program, FastPredicate? fast, Dictionary<string, object?> variables, bool readsBody)
    {
        public bool ReadsBody => readsBody;

        public bool Matches(PredicateRequest request)
        {
            if (fast?.Matches(request) is { } result)
            {
                return result;
            }

            var activation = request.Context;
            if (variables.Count > 0)
            {
                // A variable wins over a request field of the same name.
                activation = new Dictionary<string, object?>(activation, StringComparer.Ordinal);
                foreach (var (name, value) in variables)
                {
                    activation[name] = value;
                }
            }

            try
            {
                return program(activation) is true;
            }
            catch (Exception)
            {
                // A body shape the expression did not expect is no match, never a failed request.
                return false;
            }
        }
    }
}

/// <summary>
/// One request as the predicates see it. The body is parsed and the <c>Cel</c> view is built only
/// when a predicate needs them. Not thread safe: one view per evaluation.
/// </summary>
internal sealed class PredicateRequest : IDisposable
{
    private static readonly JsonElement EmptyBody = JsonDocument.Parse("{}").RootElement;

    private readonly PhotonRequest _request;
    private JsonDocument? _document;
    private JsonElement _body;
    private bool? _bodyLoaded;
    private Dictionary<string, object?>? _headers;
    private Dictionary<string, object?>? _context;

    public PredicateRequest(PhotonRequest request) => _request = request;

    /// <summary>A view whose body is already parsed; the caller owns <paramref name="body"/>.</summary>
    public PredicateRequest(PhotonRequest request, JsonElement body)
        : this(request)
    {
        _body = body;
        _bodyLoaded = true;
    }

    public PhotonRequest Request => _request;

    /// <summary>A JSON object.</summary>
    /// <exception cref="InvalidOperationException">The body is not loaded.</exception>
    public JsonElement Body => _bodyLoaded == true ? _body : throw new InvalidOperationException("The request body is not loaded.");

    public Dictionary<string, object?> Headers =>
        _headers ??= _request.Headers.ToDictionary(h => h.Key, h => (object?)h.Value, StringComparer.Ordinal);

    /// <summary>The <c>Cel</c> activation. It holds <c>body</c> once the body is loaded.</summary>
    public Dictionary<string, object?> Context
    {
        get
        {
            var context = _context ??= new(StringComparer.Ordinal)
            {
                ["service"] = _request.Metadata.Service,
                ["operation"] = _request.Metadata.Operation,
                ["method"] = _request.Method,
                ["url"] = _request.Url,
                ["headers"] = Headers,
            };
            if (_bodyLoaded == true && !context.ContainsKey("body"))
            {
                context["body"] = PredicateEvaluator.ToMap(_body);
            }

            return context;
        }
    }

    /// <summary>Parses the body on the first call. False when it is not a JSON object.</summary>
    public bool TryLoadBody()
    {
        if (_bodyLoaded is null)
        {
            _bodyLoaded = PredicateEvaluator.TryParseBody(_request.Body, out _document);
            _body = _document?.RootElement ?? EmptyBody;
        }

        return _bodyLoaded.Value;
    }

    public void Dispose() => _document?.Dispose();
}
