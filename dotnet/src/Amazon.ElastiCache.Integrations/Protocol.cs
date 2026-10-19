using System.Buffers;
using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Amazon.ElastiCache.Integrations;

/// <summary>What a <c>-CODE message</c> reply means for the request that got it.</summary>
internal enum ServerErrorKind
{
    /// <summary>Refused before any origin call, and the same on every retry.</summary>
    Deterministic,

    /// <summary>The connection holds no FAS to sign the origin call with; re-authenticate.</summary>
    Auth,

    /// <summary>A fault that may clear on retry.</summary>
    Retryable,

    /// <summary>The connection can no longer serve requests; drop it and reconnect.</summary>
    ConnectionDrop,
}

/// <summary>The error codes the cache replies with, and their kinds.</summary>
internal static class ServerErrors
{
    public const string ParseError = "PARSE_ERROR";
    public const string Unsupported = "UNSUPPORTED";
    public const string Unimplemented = "UNIMPLEMENTED";
    public const string RegionMismatch = "REGION_MISMATCH";
    public const string ConfigInvalid = "CONFIG_INVALID";
    public const string AuthError = "AUTH_ERROR";
    public const string OriginError = "ORIGIN_ERROR";
    public const string InternalError = "INTERNAL_ERROR";
    public const string Moved = "MOVED";
    public const string NoAuth = "NOAUTH";

    /// <summary>Codes are case-sensitive; an unknown code is <see cref="ServerErrorKind.Retryable"/>.</summary>
    public static ServerErrorKind Classify(string code) => code switch
    {
        ParseError or Unsupported or Unimplemented or RegionMismatch or ConfigInvalid => ServerErrorKind.Deterministic,
        AuthError => ServerErrorKind.Auth,
        Moved or NoAuth => ServerErrorKind.ConnectionDrop,
        _ => ServerErrorKind.Retryable,
    };

    /// <summary>A reply with no usable code, such as <c>-\r\n</c>, gets the code <c>ERR</c>.</summary>
    public static ElastiCacheIntegrationsServerException ToException(RespError error)
    {
        ArgumentNullException.ThrowIfNull(error);
        return IsBareCode(error.Code)
            ? new ElastiCacheIntegrationsServerException(error.Code, error.Message)
            : new ElastiCacheIntegrationsServerException("ERR", error.Code.Length == 0 ? error.Message : error.Message.Length == 0 ? error.Code : $"{error.Code} {error.Message}");
    }

    private static bool IsBareCode(string code) => code.Length > 0 && code[0] != '-' && !code.Any(char.IsWhiteSpace);
}

/// <summary>A reply to <c>aws.request</c>, read from the FIFO slot or from an <c>aws.response</c> push.</summary>
internal abstract record RequestReply
{
    private RequestReply()
    {
    }

    /// <summary>A <c>[status, headers, body]</c> reply, DynamoDB errors included.</summary>
    public sealed record Served(ElastiCacheIntegrationsResponse Response) : RequestReply;

    /// <summary>A <c>+id</c> reply: the response arrives later in a push with this id.</summary>
    public sealed record Deferred(string RequestId) : RequestReply;

    /// <summary>A <c>-CODE message</c> reply.</summary>
    public sealed record Rejected(ElastiCacheIntegrationsServerException Error) : RequestReply;
}

/// <summary>Builds the cache commands and reads their replies. Stateless and thread safe.</summary>
internal static class ElastiCacheIntegrationsProtocol
{
    public const string ConfigCommand = "aws.config";
    public const string RequestCommand = "aws.request";
    public const string ResponsePushKind = "aws.response";

    /// <summary>The config format this client reads, sent as <c>version</c> in the overrides.</summary>
    public const string ConfigVersion = "1.0";

    private const string OkReply = "OK";

    private static readonly HashSet<string> ExcludedHeaders =
        ["authorization", "x-amz-date", "x-amz-security-token", "x-amz-request-id", "amz-sdk-request"];

    private static readonly JsonSerializerOptions RelaxedJson = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    /// <summary>
    /// The overrides as compact JSON: <c>version</c> first, then the modeled fields, then
    /// <see cref="ElastiCacheIntegrationsCachePolicy.Extra"/>. Non-ASCII is escaped as lowercase <c>\uXXXX</c>.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException">Extra sets <c>version</c>.</exception>
    public static byte[] SerializeOverrides(ElastiCacheIntegrationsOverrides overrides)
    {
        ArgumentNullException.ThrowIfNull(overrides);
        var fields = JsonSerializer.SerializeToElement(overrides, RelaxedJson);
        var buffer = new ArrayBufferWriter<byte>();
        using (var writer = new Utf8JsonWriter(buffer, new JsonWriterOptions { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping }))
        {
            writer.WriteStartObject();
            writer.WriteString("version", ConfigVersion);
            foreach (var field in fields.EnumerateObject())
            {
                if (field.NameEquals("version"))
                {
                    throw new ElastiCacheIntegrationsConfigurationException("Overrides.Extra cannot set \"version\"; the client sends it.");
                }

                field.WriteTo(writer);
            }

            writer.WriteEndObject();
        }

        return Encoding.ASCII.GetBytes(EscapeNonAscii(Encoding.UTF8.GetString(buffer.WrittenSpan)));
    }

    /// <summary>Writes <c>aws.config overrides</c>, the form every new connection sends first.</summary>
    public static void WriteConfig(IBufferWriter<byte> writer, ReadOnlyMemory<byte> overridesJson) =>
        RespEncoder.WriteCommand(writer, [ConfigCommand, overridesJson]);

    /// <summary>Writes the no-argument <c>aws.config</c>: the overrides are unchanged.</summary>
    public static void WriteConfigRefresh(IBufferWriter<byte> writer) => RespEncoder.WriteCommand(writer, [ConfigCommand]);

    /// <summary>Reads the reply to <c>aws.config</c>. Null means unchanged, and is only valid for the no-argument form.</summary>
    /// <exception cref="ElastiCacheIntegrationsConfigurationException"><c>-CONFIG_INVALID</c>; the cause is the server error.</exception>
    /// <exception cref="ElastiCacheIntegrationsServerException">Any other error reply.</exception>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">Any other shape.</exception>
    public static ResolvedConfig? ParseConfigReply(RespValue value, bool noArgumentForm)
    {
        switch (value)
        {
            case RespNull when noArgumentForm:
                return null;
            case RespError { Code: ServerErrors.ConfigInvalid } error:
                var cause = ServerErrors.ToException(error);
                throw new ElastiCacheIntegrationsConfigurationException($"The cache rejected the config overrides: {cause.ServerMessage}", cause);
            case RespError error:
                throw ServerErrors.ToException(error);
            case RespBlobString blob:
                return ParseConfig(blob.Value);
            default:
                throw MalformedConfig($"expected a JSON bulk string, got {Describe(value)}");
        }
    }

    private static ResolvedConfig ParseConfig(ReadOnlyMemory<byte> json)
    {
        _ = Utf8(json.Span, $"{ConfigCommand} reply");
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException e)
        {
            throw new ElastiCacheIntegrationsProtocolException($"Malformed {ConfigCommand} reply: invalid JSON.", e);
        }

        using (document)
        {
            var root = document.RootElement;
            return root.ValueKind == JsonValueKind.Object
                ? new ResolvedConfig(ReadVersion(root), ReadRefreshInterval(root), root)
                : throw MalformedConfig($"config must be a JSON object, got {root.ValueKind}");
        }
    }

    private static string? ReadVersion(JsonElement root)
    {
        if (!root.TryGetProperty("version", out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        // PredicateEvaluator.Compile checks the format major.
        return value.ValueKind == JsonValueKind.String ? value.GetString()! : throw MalformedConfig("version is not a string");
    }

    /// <summary>
    /// Required: a positive whole number of seconds. <c>60.0</c> is refused, as Python does. A value
    /// past the longest timer delay is capped to it.
    /// </summary>
    private static TimeSpan ReadRefreshInterval(JsonElement root)
    {
        if (!root.TryGetProperty("refresh_interval", out var value))
        {
            throw MalformedConfig("refresh_interval is missing");
        }

        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out var seconds) || seconds <= 0)
        {
            throw MalformedConfig("refresh_interval must be a positive whole number of seconds");
        }

        return TimeSpan.FromSeconds(Math.Min(seconds, MaxRefreshSeconds));
    }

    /// <summary>The longest delay a .NET timer takes, <see cref="int.MaxValue"/> milliseconds.</summary>
    private const long MaxRefreshSeconds = int.MaxValue / 1000;

    private static ElastiCacheIntegrationsProtocolException MalformedConfig(string detail) => new($"Malformed {ConfigCommand} reply: {detail}.");

    /// <summary>
    /// Escapes every UTF-16 unit above U+007F and lowercases existing escapes. Matches Node byte
    /// for byte except U+007F, which System.Text.Json escapes and Python <c>json.dumps</c> does too.
    /// </summary>
    private static string EscapeNonAscii(string json)
    {
        var text = new StringBuilder(json.Length);
        for (var i = 0; i < json.Length; i++)
        {
            var c = json[i];
            if (c == '\\' && i + 1 < json.Length)
            {
                text.Append(c).Append(json[++i]);
                if (json[i] == 'u' && i + 4 < json.Length)
                {
                    text.Append(json.AsSpan(i + 1, 4).ToString().ToLowerInvariant());
                    i += 4;
                }
            }
            else if (c > '\x7F')
            {
                text.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
            }
            else
            {
                text.Append(c);
            }
        }

        return text.ToString();
    }

    /// <summary>Writes <c>aws.request method url headers body metadata</c>; nothing is written on failure.</summary>
    /// <exception cref="ArgumentException">Text that is not valid UTF-16.</exception>
    public static void WriteRequest(IBufferWriter<byte> writer, ElastiCacheIntegrationsRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        RespEncoder.WriteCommand(
            writer,
            [RequestCommand, request.Method, request.Url, NormalizeHeaders(request.Headers), request.Body, FormatMetadata(request.Metadata)]);
    }

    /// <summary>
    /// Lowercased, trimmed <c>name:value</c> lines without the volatile and auth headers,
    /// sorted ordinally by name then value, joined with LF.
    /// </summary>
    internal static string NormalizeHeaders(IReadOnlyDictionary<string, string> headers)
    {
        var pairs = new List<(string Name, string Value)>(headers.Count);
        foreach (var (name, value) in headers)
        {
            var normalized = name.Trim().ToLowerInvariant();
            if (!ExcludedHeaders.Contains(normalized))
            {
                pairs.Add((normalized, value.Trim()));
            }
        }

        pairs.Sort(static (a, b) =>
        {
            var byName = string.CompareOrdinal(a.Name, b.Name);
            return byName != 0 ? byName : string.CompareOrdinal(a.Value, b.Value);
        });
        return string.Join('\n', pairs.Select(static p => $"{p.Name}:{p.Value}"));
    }

    internal static string FormatMetadata(RequestMetadata metadata) => $"service={metadata.Service}&op={metadata.Operation}";

    /// <summary>Reads one reply. <c>+id</c> is only valid in band, never inside a push.</summary>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">Any other shape.</exception>
    public static RequestReply ParseRequestReply(RespValue value, bool inBand = true) => value switch
    {
        RespError error => new RequestReply.Rejected(ServerErrors.ToException(error)),
        RespSimpleString { Value: var id } when inBand && id.Length > 0 && id != OkReply => new RequestReply.Deferred(id),
        RespArray { Items.Count: 3 } array => new RequestReply.Served(ParseResponse(array.Items)),
        _ => throw Malformed($"expected [status, headers, body], got {Describe(value)}"),
    };

    /// <summary>Splits an <c>[aws.response, id, reply]</c> push. A bad envelope leaves the stream untrustworthy.</summary>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">Wrong item count, kind or id.</exception>
    public static (string RequestId, RespValue Reply) ParseResponsePush(RespPush push)
    {
        ArgumentNullException.ThrowIfNull(push);
        if (push.Items.Count == 0 || Text(push.Items[0], "push kind") != ResponsePushKind)
        {
            throw Malformed($"unknown push kind {(push.Items.Count == 0 ? "(none)" : Describe(push.Items[0]))}");
        }

        if (push.Items.Count != 3)
        {
            throw Malformed($"an {ResponsePushKind} push takes 3 items, got {push.Items.Count}");
        }

        var id = Text(push.Items[1], "push request id");
        if (string.IsNullOrEmpty(id))
        {
            throw Malformed($"invalid push request id {Describe(push.Items[1])}");
        }

        return push.Items[2] is RespPush
            ? throw Malformed("a push nested in a push")
            : (id, push.Items[2]);
    }

    private static ElastiCacheIntegrationsResponse ParseResponse(IReadOnlyList<RespValue> items)
    {
        var status = ParseStatus(items[0]);
        if (status is < 100 or > 599)
        {
            throw Malformed($"status code out of range (100-599): {status}");
        }

        if (items[1] is not RespBlobString headers || items[2] is not RespBlobString body)
        {
            throw Malformed($"headers={Describe(items[1])}, body={Describe(items[2])}");
        }

        return new ElastiCacheIntegrationsResponse((int)status, ParseHeaders(headers.Value.Span), body.Value);
    }

    private static long ParseStatus(RespValue value)
    {
        if (value is RespInteger integer)
        {
            return integer.Value;
        }

        var text = Text(value, "status code")?.Trim();
        var digits = text is ['+' or '-', .. var rest] ? rest : text;
        return !string.IsNullOrEmpty(digits) && digits.All(char.IsAsciiDigit)
            && long.TryParse(text, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var status)
            ? status
            : throw Malformed($"invalid status code {Describe(value)}");
    }

    private static Dictionary<string, string> ParseHeaders(ReadOnlySpan<byte> raw)
    {
        var headers = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in Utf8(raw, "response headers").Split('\n'))
        {
            var colon = line.IndexOf(':');
            if (colon >= 0)
            {
                headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
            }
        }

        return headers;
    }

    /// <summary>The text of a blob or simple string; null for any other type.</summary>
    private static string? Text(RespValue value, string what) => value switch
    {
        RespBlobString blob => Utf8(blob.Value.Span, what),
        RespSimpleString simple => simple.Value,
        _ => null,
    };

    private static string Utf8(ReadOnlySpan<byte> bytes, string what)
    {
        try
        {
            return RespEncoder.StrictUtf8.GetString(bytes);
        }
        catch (DecoderFallbackException e)
        {
            throw new ElastiCacheIntegrationsProtocolException($"Invalid UTF-8 in {what}.", e);
        }
    }

    private static string Describe(RespValue value) => value switch
    {
        RespAggregate aggregate => $"{aggregate.GetType().Name} of {aggregate.Items.Count}",
        RespInteger integer => $"integer {integer.Value}",
        _ => value.GetType().Name,
    };

    private static ElastiCacheIntegrationsProtocolException Malformed(string detail) => new($"Malformed reply: {detail}.");
}
