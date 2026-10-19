using System.Buffers;
using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.Numerics;
using System.Text;

namespace Amazon.ElastiCache.Integrations;

/// <summary>A RESP2 or RESP3 value. Equality compares content, including blob bytes.</summary>
internal abstract record RespValue;

/// <summary><c>+text</c>.</summary>
internal sealed record RespSimpleString(string Value) : RespValue;

/// <summary><c>-CODE message</c>, or <c>!len</c> when <paramref name="IsBlob"/>.</summary>
internal sealed record RespError(string Code, string Message, bool IsBlob = false) : RespValue;

/// <summary><c>:n</c>.</summary>
internal sealed record RespInteger(long Value) : RespValue;

/// <summary><c>(n</c>.</summary>
internal sealed record RespBigNumber(BigInteger Value) : RespValue;

/// <summary><c>,d</c>, including <c>inf</c>, <c>-inf</c> and <c>nan</c>.</summary>
internal sealed record RespDouble(double Value) : RespValue;

/// <summary><c>#t</c> or <c>#f</c>.</summary>
internal sealed record RespBoolean(bool Value) : RespValue;

/// <summary><c>_</c>, and the RESP2 nulls <c>$-1</c> and <c>*-1</c>.</summary>
internal sealed record RespNull : RespValue
{
    private RespNull()
    {
    }

    public static RespNull Instance { get; } = new();
}

/// <summary><c>$len</c>: binary-safe bytes.</summary>
internal sealed record RespBlobString(ReadOnlyMemory<byte> Value) : RespValue
{
    public bool Equals(RespBlobString? other) => other is not null && Value.Span.SequenceEqual(other.Value.Span);

    public override int GetHashCode() => Value.Length;
}

/// <summary><c>=len</c>: a three-character format such as <c>txt</c>, then the bytes.</summary>
internal sealed record RespVerbatimString(string Format, ReadOnlyMemory<byte> Value) : RespValue
{
    public bool Equals(RespVerbatimString? other) =>
        other is not null && Format == other.Format && Value.Span.SequenceEqual(other.Value.Span);

    public override int GetHashCode() => HashCode.Combine(Format, Value.Length);
}

/// <summary>An array, set or push: an ordered list of values.</summary>
internal abstract record RespAggregate(IReadOnlyList<RespValue> Items) : RespValue
{
    public virtual bool Equals(RespAggregate? other) =>
        other is not null && EqualityContract == other.EqualityContract && Items.SequenceEqual(other.Items);

    public override int GetHashCode() => HashCode.Combine(EqualityContract, Items.Count);
}

/// <summary><c>*n</c>.</summary>
internal sealed record RespArray(IReadOnlyList<RespValue> Items) : RespAggregate(Items);

/// <summary><c>~n</c>, in wire order.</summary>
internal sealed record RespSet(IReadOnlyList<RespValue> Items) : RespAggregate(Items);

/// <summary><c>&gt;n</c>: an out-of-band message; the first item is its kind.</summary>
internal sealed record RespPush(IReadOnlyList<RespValue> Items) : RespAggregate(Items);

/// <summary><c>%n</c>, in wire order.</summary>
internal sealed record RespMap(IReadOnlyList<KeyValuePair<RespValue, RespValue>> Entries) : RespValue
{
    public bool Equals(RespMap? other)
    {
        if (other is null || Entries.Count != other.Entries.Count)
        {
            return false;
        }

        for (var i = 0; i < Entries.Count; i++)
        {
            if (!Entries[i].Key.Equals(other.Entries[i].Key) || !Entries[i].Value.Equals(other.Entries[i].Value))
            {
                return false;
            }
        }

        return true;
    }

    public override int GetHashCode() => Entries.Count;
}

/// <summary>A command argument: text, written as strict UTF-8, or bytes, written as they are.</summary>
internal readonly struct RespArg
{
    private readonly string? _text;
    private readonly ReadOnlyMemory<byte> _bytes;

    private RespArg(string? text, ReadOnlyMemory<byte> bytes)
    {
        _text = text;
        _bytes = bytes;
    }

    public static implicit operator RespArg(string text) => new(text ?? throw new ArgumentNullException(nameof(text)), default);

    public static implicit operator RespArg(ReadOnlyMemory<byte> bytes) => new(null, bytes);

    public static implicit operator RespArg(Memory<byte> bytes) => new(null, bytes);

    public static implicit operator RespArg(byte[] bytes) => new(null, bytes ?? throw new ArgumentNullException(nameof(bytes)));

    /// <summary>Throws <see cref="EncoderFallbackException"/> for text that is not valid UTF-16.</summary>
    internal int ByteCount => _text is null ? _bytes.Length : RespEncoder.StrictUtf8.GetByteCount(_text);

    internal void WriteBulk(IBufferWriter<byte> writer, int byteCount)
    {
        RespEncoder.WriteHeader(writer, (byte)'$', byteCount);
        if (_text is null)
        {
            RespEncoder.WriteBytes(writer, _bytes.Span);
        }
        else
        {
            var span = writer.GetSpan(byteCount);
            writer.Advance(RespEncoder.StrictUtf8.GetBytes(_text, span));
        }

        RespEncoder.WriteBytes(writer, "\r\n"u8);
    }
}

/// <summary>Writes RESP into an <see cref="IBufferWriter{T}"/>, such as a <c>PipeWriter</c>.</summary>
internal static class RespEncoder
{
    internal static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    /// <summary>The deepest aggregate nesting written or read; replies from the cache nest three levels.</summary>
    internal const int MaxDepth = 64;

    // A type byte, a length of up to 20 digits and CRLF, rounded up.
    private const int MaxHeaderLength = 24;

    /// <inheritdoc cref="WriteCommand(IBufferWriter{byte}, ReadOnlySpan{RespArg})"/>
    public static void WriteCommand(IBufferWriter<byte> writer, params RespArg[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        WriteCommand(writer, args.AsSpan());
    }

    /// <summary>
    /// Writes <c>*N</c> and one bulk string per argument; the first argument is the command name.
    /// Every argument is checked before the first byte is written, so a bad one leaves the writer untouched.
    /// </summary>
    /// <exception cref="ArgumentException">No arguments, or text that is not valid UTF-16 (<see cref="EncoderFallbackException"/>).</exception>
    public static void WriteCommand(IBufferWriter<byte> writer, ReadOnlySpan<RespArg> args)
    {
        ArgumentNullException.ThrowIfNull(writer);
        if (args.IsEmpty)
        {
            throw new ArgumentException("A command needs at least its name.", nameof(args));
        }

        var counts = args.Length <= 16 ? stackalloc int[args.Length] : new int[args.Length];
        long size = MaxHeaderLength;
        for (var i = 0; i < args.Length; i++)
        {
            counts[i] = args[i].ByteCount;
            size += MaxHeaderLength + counts[i] + 2;
        }

        // One buffer for the whole frame, instead of growing it while writing a large body.
        _ = writer.GetSpan((int)Math.Min(size, Array.MaxLength));
        WriteHeader(writer, (byte)'*', args.Length);
        for (var i = 0; i < args.Length; i++)
        {
            args[i].WriteBulk(writer, counts[i]);
        }
    }

    /// <summary>
    /// Writes one value as RESP3. The whole value is checked first, so a bad one leaves the writer untouched.
    /// </summary>
    public static void WriteValue(IBufferWriter<byte> writer, RespValue value)
    {
        ArgumentNullException.ThrowIfNull(writer);
        Check(value, 0);
        Write(writer, value);
    }

    private static void Check(RespValue value, int depth)
    {
        if (depth > MaxDepth)
        {
            throw new ArgumentException($"RESP nesting is deeper than {MaxDepth}.", nameof(value));
        }

        switch (value)
        {
            case null:
                throw new ArgumentNullException(nameof(value));
            case RespSimpleString s:
                CheckText(s.Value, line: true);
                break;
            case RespError e:
                CheckText(e.Message, line: !e.IsBlob);
                CheckText(e.Code, line: !e.IsBlob);
                if (e.Code.Contains(' '))
                {
                    throw new ArgumentException("An error code cannot contain a space.", nameof(value));
                }

                break;
            case RespVerbatimString v:
                if (v.Format is not { Length: 3 } || !Ascii.IsValid(v.Format) || v.Format.AsSpan().IndexOfAny(":\r\n") >= 0)
                {
                    throw new ArgumentException("A verbatim format is three ASCII characters.", nameof(value));
                }

                break;
            case RespAggregate a:
                foreach (var item in a.Items)
                {
                    Check(item, depth + 1);
                }

                break;
            case RespMap m:
                foreach (var (k, v) in m.Entries)
                {
                    Check(k, depth + 1);
                    Check(v, depth + 1);
                }

                break;
        }
    }

    private static void CheckText(string text, bool line)
    {
        _ = StrictUtf8.GetByteCount(text);
        if (line && text.AsSpan().IndexOfAny('\r', '\n') >= 0)
        {
            throw new ArgumentException("A RESP line cannot contain CR or LF.", "value");
        }
    }

    private static void Write(IBufferWriter<byte> writer, RespValue value)
    {
        switch (value)
        {
            case RespSimpleString s:
                WriteLine(writer, (byte)'+', s.Value);
                break;
            case RespError { IsBlob: false } e:
                WriteLine(writer, (byte)'-', ErrorText(e));
                break;
            case RespError e:
                WriteBlob(writer, (byte)'!', StrictUtf8.GetBytes(ErrorText(e)));
                break;
            case RespInteger i:
                WriteHeader(writer, (byte)':', i.Value);
                break;
            case RespBigNumber b:
                WriteLine(writer, (byte)'(', b.Value.ToString(CultureInfo.InvariantCulture));
                break;
            case RespDouble d:
                WriteLine(writer, (byte)',', FormatDouble(d.Value));
                break;
            case RespBoolean b:
                WriteBytes(writer, b.Value ? "#t\r\n"u8 : "#f\r\n"u8);
                break;
            case RespNull:
                WriteBytes(writer, "_\r\n"u8);
                break;
            case RespBlobString b:
                WriteBlob(writer, (byte)'$', b.Value.Span);
                break;
            case RespVerbatimString v:
                WriteHeader(writer, (byte)'=', v.Value.Length + 4L);
                WriteBytes(writer, [(byte)v.Format[0], (byte)v.Format[1], (byte)v.Format[2], (byte)':']);
                WriteBytes(writer, v.Value.Span);
                WriteBytes(writer, "\r\n"u8);
                break;
            case RespAggregate a:
                WriteHeader(writer, a switch { RespArray => (byte)'*', RespSet => (byte)'~', _ => (byte)'>' }, a.Items.Count);
                foreach (var item in a.Items)
                {
                    Write(writer, item);
                }

                break;
            case RespMap m:
                WriteHeader(writer, (byte)'%', m.Entries.Count);
                foreach (var (k, v) in m.Entries)
                {
                    Write(writer, k);
                    Write(writer, v);
                }

                break;
            default:
                throw new ArgumentException($"Unknown RESP value {value.GetType().Name}.", nameof(value));
        }
    }

    private static string ErrorText(RespError e) => e.Message.Length == 0 ? e.Code : $"{e.Code} {e.Message}";

    private static string FormatDouble(double d) => d switch
    {
        double.PositiveInfinity => "inf",
        double.NegativeInfinity => "-inf",
        _ when double.IsNaN(d) => "nan",
        _ => d.ToString("R", CultureInfo.InvariantCulture),
    };

    private static void WriteLine(IBufferWriter<byte> writer, byte prefix, string text)
    {
        writer.GetSpan(1)[0] = prefix;
        writer.Advance(1);
        StrictUtf8.GetBytes(text, writer);
        WriteBytes(writer, "\r\n"u8);
    }

    private static void WriteBlob(IBufferWriter<byte> writer, byte prefix, ReadOnlySpan<byte> bytes)
    {
        WriteHeader(writer, prefix, bytes.Length);
        WriteBytes(writer, bytes);
        WriteBytes(writer, "\r\n"u8);
    }

    internal static void WriteHeader(IBufferWriter<byte> writer, byte prefix, long length)
    {
        var span = writer.GetSpan(MaxHeaderLength);
        span[0] = prefix;
        length.TryFormat(span[1..], out var written, default, CultureInfo.InvariantCulture);
        "\r\n"u8.CopyTo(span[(1 + written)..]);
        writer.Advance(written + 3);
    }

    internal static void WriteBytes(IBufferWriter<byte> writer, ReadOnlySpan<byte> bytes)
    {
        while (!bytes.IsEmpty)
        {
            var span = writer.GetSpan();
            var n = Math.Min(span.Length, bytes.Length);
            bytes[..n].CopyTo(span);
            writer.Advance(n);
            bytes = bytes[n..];
        }
    }
}

/// <summary>
/// Decodes RESP2 and RESP3 values from a growing buffer, such as a <c>PipeReader</c>'s. A value
/// may arrive split across any number of reads; nothing is consumed until it is complete.
/// </summary>
internal static class RespDecoder
{
    internal const int MaxDepth = RespEncoder.MaxDepth;

    /// <summary>Caps the parse cost of a <c>(</c> value; cache replies carry none.</summary>
    internal const int MaxBigNumberLength = 1024;

    /// <summary>
    /// Decodes the value at the start of <paramref name="buffer"/> and slices it off. Returns
    /// false, with <paramref name="buffer"/> unchanged, when the value is not complete yet.
    /// </summary>
    /// <exception cref="ElastiCacheIntegrationsProtocolException">The bytes can never form a valid value.</exception>
    public static bool TryDecode(ref ReadOnlySequence<byte> buffer, [NotNullWhen(true)] out RespValue? value)
    {
        var reader = new SequenceReader<byte>(buffer);
        if (!TryRead(ref reader, 0, out value))
        {
            return false;
        }

        buffer = buffer.Slice(reader.Position);
        return true;
    }

    private static bool TryRead(ref SequenceReader<byte> reader, int depth, [NotNullWhen(true)] out RespValue? value)
    {
        value = null;
        if (depth > MaxDepth)
        {
            throw Corrupt($"RESP nesting is deeper than {MaxDepth}");
        }

        while (true)
        {
            if (!reader.TryRead(out var prefix))
            {
                return false;
            }

            if (!"+-:,(#_$!=*~>%|"u8.Contains(prefix))
            {
                throw Corrupt($"Unsupported RESP prefix 0x{prefix:X2}");
            }

            if (!reader.TryReadTo(out ReadOnlySpan<byte> line, "\r\n"u8))
            {
                return false;
            }

            switch (prefix)
            {
                case (byte)'+':
                    value = new RespSimpleString(StrictText(SingleLine(line), "simple string"));
                    return true;
                case (byte)'-':
                    value = ParseError(Encoding.UTF8.GetString(SingleLine(line)), isBlob: false);
                    return true;
                case (byte)':':
                    value = new RespInteger(ParseLong(line, "integer"));
                    return true;
                case (byte)',':
                    value = new RespDouble(ParseDouble(line));
                    return true;
                case (byte)'(':
                    value = new RespBigNumber(ParseBigNumber(line));
                    return true;
                case (byte)'#':
                    value = line.SequenceEqual("t"u8) ? new RespBoolean(true)
                        : line.SequenceEqual("f"u8) ? new RespBoolean(false)
                        : throw Corrupt("Malformed RESP boolean");
                    return true;
                case (byte)'_':
                    value = line.IsEmpty ? RespNull.Instance : throw Corrupt("Malformed RESP null");
                    return true;
                case (byte)'$' or (byte)'!' or (byte)'=':
                    return TryReadBlob(ref reader, prefix, ParseLong(line, "length"), out value);
                case (byte)'%':
                    return TryReadMap(ref reader, depth, ParseCount(line, 2), out value);
                case (byte)'|':
                    // Attributes are metadata for the value that follows; it is decoded and they are dropped.
                    if (!TryReadMap(ref reader, depth, ParseCount(line, 2), out _))
                    {
                        return false;
                    }

                    continue;
                default:
                    return TryReadAggregate(ref reader, prefix, depth, line, out value);
            }
        }
    }

    private static bool TryReadBlob(ref SequenceReader<byte> reader, byte prefix, long length, [NotNullWhen(true)] out RespValue? value)
    {
        value = null;
        if (prefix == '$' && length == -1)
        {
            value = RespNull.Instance;
            return true;
        }

        if (length < (prefix == '=' ? 4 : 0) || length > Array.MaxLength)
        {
            throw Corrupt($"Malformed RESP blob length {length}");
        }

        if (reader.Remaining < length + 2)
        {
            return false;
        }

        var bytes = GC.AllocateUninitializedArray<byte>((int)length);
        reader.TryCopyTo(bytes);
        reader.Advance(length);
        if (!reader.IsNext("\r\n"u8, advancePast: true))
        {
            throw Corrupt("Malformed RESP blob terminator");
        }

        value = prefix switch
        {
            (byte)'$' => new RespBlobString(bytes),
            (byte)'!' => ParseError(Encoding.UTF8.GetString(bytes), isBlob: true),
            _ when bytes[3] == ':' && Ascii.IsValid(bytes.AsSpan(0, 3)) && bytes.AsSpan(0, 3).IndexOfAny(":\r\n"u8) < 0 =>
                new RespVerbatimString(Encoding.ASCII.GetString(bytes, 0, 3), bytes.AsMemory(4)),
            _ => throw Corrupt("Malformed RESP verbatim string format"),
        };
        return true;
    }

    private static bool TryReadAggregate(
        ref SequenceReader<byte> reader, byte prefix, int depth, ReadOnlySpan<byte> line, [NotNullWhen(true)] out RespValue? value)
    {
        value = null;
        if (prefix == '*' && line.SequenceEqual("-1"u8))
        {
            value = RespNull.Instance;
            return true;
        }

        var count = ParseCount(line, 1);
        var items = new List<RespValue>(Math.Min(count, 1024));
        for (var i = 0; i < count; i++)
        {
            if (!TryRead(ref reader, depth + 1, out var item))
            {
                return false;
            }

            items.Add(item);
        }

        value = prefix switch
        {
            (byte)'*' => new RespArray(items),
            (byte)'~' => new RespSet(items),
            _ => new RespPush(items),
        };
        return true;
    }

    private static bool TryReadMap(ref SequenceReader<byte> reader, int depth, int count, [NotNullWhen(true)] out RespValue? value)
    {
        value = null;
        var entries = new List<KeyValuePair<RespValue, RespValue>>(Math.Min(count, 1024));
        for (var i = 0; i < count; i++)
        {
            if (!TryRead(ref reader, depth + 1, out var key) || !TryRead(ref reader, depth + 1, out var item))
            {
                return false;
            }

            entries.Add(new(key, item));
        }

        value = new RespMap(entries);
        return true;
    }

    private static RespError ParseError(string text, bool isBlob)
    {
        var space = text.IndexOf(' ');
        return space < 0 ? new RespError(text, string.Empty, isBlob) : new RespError(text[..space], text[(space + 1)..], isBlob);
    }

    private static int ParseCount(ReadOnlySpan<byte> line, int itemsPerEntry)
    {
        var count = ParseLong(line, "length");
        return count >= 0 && count <= Array.MaxLength / itemsPerEntry
            ? (int)count
            : throw Corrupt($"Malformed RESP length {count}");
    }

    private static long ParseLong(ReadOnlySpan<byte> line, string what) =>
        long.TryParse(line, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var n)
            ? n
            : throw Corrupt($"Malformed RESP {what}: {Printable(line)}");

    private static double ParseDouble(ReadOnlySpan<byte> line)
    {
        if (line.SequenceEqual("inf"u8) || line.SequenceEqual("+inf"u8))
        {
            return double.PositiveInfinity;
        }

        if (line.SequenceEqual("-inf"u8))
        {
            return double.NegativeInfinity;
        }

        if (line.SequenceEqual("nan"u8) || line.SequenceEqual("-nan"u8))
        {
            return double.NaN;
        }

        const NumberStyles style = NumberStyles.AllowLeadingSign | NumberStyles.AllowDecimalPoint | NumberStyles.AllowExponent;
        return line.IndexOfAnyExcept("+-.0123456789eE"u8) < 0 && double.TryParse(line, style, CultureInfo.InvariantCulture, out var d)
            ? d
            : throw Corrupt($"Malformed RESP double: {Printable(line)}");
    }

    private static BigInteger ParseBigNumber(ReadOnlySpan<byte> line)
    {
        if (line.Length > MaxBigNumberLength)
        {
            throw Corrupt($"RESP big number is longer than {MaxBigNumberLength} characters");
        }

        var digits = line.Length > 0 && (line[0] == '-' || line[0] == '+') ? line[1..] : line;
        return !digits.IsEmpty && digits.IndexOfAnyExceptInRange((byte)'0', (byte)'9') < 0
            && BigInteger.TryParse(Encoding.ASCII.GetString(line), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var n)
            ? n
            : throw Corrupt($"Malformed RESP big number: {Printable(line)}");
    }

    private static ReadOnlySpan<byte> SingleLine(ReadOnlySpan<byte> line) =>
        line.IndexOfAny((byte)'\r', (byte)'\n') < 0 ? line : throw Corrupt("RESP line contains a bare CR or LF");

    private static string StrictText(ReadOnlySpan<byte> line, string what)
    {
        try
        {
            return RespEncoder.StrictUtf8.GetString(line);
        }
        catch (DecoderFallbackException e)
        {
            throw new ElastiCacheIntegrationsProtocolException($"Malformed RESP {what}: not UTF-8", e);
        }
    }

    private static string Printable(ReadOnlySpan<byte> line) =>
        Encoding.UTF8.GetString(line[..Math.Min(line.Length, 32)]).ReplaceLineEndings(" ");

    private static ElastiCacheIntegrationsProtocolException Corrupt(string message) => new(message);
}
