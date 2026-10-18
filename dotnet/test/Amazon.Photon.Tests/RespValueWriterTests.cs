using System.Buffers;
using System.Numerics;
using System.Text;

namespace Amazon.Photon.Tests;

public class RespValueWriterTests
{
    private static RespBlobString Blob(string s) => new(Encoding.UTF8.GetBytes(s));

    public static TheoryData<string, object> Values => new()
    {
        { "+OK\r\n", new RespSimpleString("OK") },
        { "+\r\n", new RespSimpleString("") },
        { "-UNSUPPORTED op Foo\r\n", new RespError("UNSUPPORTED", "op Foo") },
        { "-AUTH_ERROR\r\n", new RespError("AUTH_ERROR", "") },
        { "!21\r\nSYNTAX invalid syntax\r\n", new RespError("SYNTAX", "invalid syntax", IsBlob: true) },
        { "!8\r\nERR a\r\nb\r\n", new RespError("ERR", "a\r\nb", IsBlob: true) },
        { ":-42\r\n", new RespInteger(-42) },
        { ":9223372036854775807\r\n", new RespInteger(long.MaxValue) },
        { "(-3492890328409238509324850943850943825024385\r\n", new RespBigNumber(BigInteger.Parse("-3492890328409238509324850943850943825024385")) },
        { ",1.5\r\n", new RespDouble(1.5) },
        { ",1E+300\r\n", new RespDouble(1e300) },
        { ",inf\r\n", new RespDouble(double.PositiveInfinity) },
        { ",-inf\r\n", new RespDouble(double.NegativeInfinity) },
        { ",nan\r\n", new RespDouble(double.NaN) },
        { "#t\r\n", new RespBoolean(true) },
        { "#f\r\n", new RespBoolean(false) },
        { "_\r\n", RespNull.Instance },
        { "$5\r\nhe\r\no\r\n", Blob("he\r\no") },
        { "$0\r\n\r\n", Blob("") },
        { "=15\r\ntxt:Some string\r\n", new RespVerbatimString("txt", "Some string"u8.ToArray()) },
        { "*2\r\n:1\r\n*0\r\n", new RespArray([new RespInteger(1), new RespArray([])]) },
        { "~2\r\n+a\r\n+b\r\n", new RespSet([new RespSimpleString("a"), new RespSimpleString("b")]) },
        { ">2\r\n$12\r\naws.response\r\n+id\r\n", new RespPush([Blob("aws.response"), new RespSimpleString("id")]) },
        { "%1\r\n+k\r\n%0\r\n", new RespMap([new(new RespSimpleString("k"), new RespMap([]))]) },
    };

    private static RespValue Nested(int depth)
    {
        RespValue value = new RespInteger(1);
        for (var i = 0; i < depth; i++)
        {
            value = new RespArray([value]);
        }

        return value;
    }

    [Fact]
    public void WritesTheDeepestNestingAllowed()
    {
        Assert.StartsWith("*1\r\n*1\r\n", Write(Nested(RespEncoder.MaxDepth)));
    }

    private static string Write(RespValue value)
    {
        var writer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteValue(writer, value);
        return Encoding.UTF8.GetString(writer.WrittenSpan);
    }

    [Theory]
    [MemberData(nameof(Values))]
    public void WritesEveryType(string wire, object value)
    {
        Assert.Equal(wire, Write((RespValue)value));
    }

    [Fact]
    public void WritesBinaryBlobsAsTheyAre()
    {
        var writer = new ArrayBufferWriter<byte>();
        RespEncoder.WriteValue(writer, new RespBlobString(new byte[] { 0, 255 }));
        Assert.Equal([.. "$2\r\n"u8, 0, 255, .. "\r\n"u8], writer.WrittenSpan.ToArray());
    }

    public static IEnumerable<object[]> Invalid => new RespValue[]
    {
        new RespSimpleString("a\r\nb"),
        new RespSimpleString("\ud800"),
        new RespError("BAD CODE", "m"),
        new RespError("CODE", "line\nbreak"),
        new RespError("BAD CODE", "m", IsBlob: true),
        new RespVerbatimString(null!, "x"u8.ToArray()),
        Nested(RespEncoder.MaxDepth + 1),
        new RespVerbatimString("text", "x"u8.ToArray()),
        new RespVerbatimString("t:t", "x"u8.ToArray()),
        new RespVerbatimString("tx\u00e9", "x"u8.ToArray()),
        new RespArray([new RespInteger(1), new RespSimpleString("\n")]),
        new RespMap([new(new RespInteger(1), new RespPush([new RespSimpleString("\r")]))]),
    }.Select(v => new object[] { v });

    [Theory]
    [MemberData(nameof(Invalid))]
    public void InvalidValuesWriteNothing(object value)
    {
        var writer = new ArrayBufferWriter<byte>();
        Assert.ThrowsAny<ArgumentException>(() => RespEncoder.WriteValue(writer, (RespValue)value));
        Assert.Equal(0, writer.WrittenCount);
    }

    [Fact]
    public void RejectsNull()
    {
        Assert.Throws<ArgumentNullException>(() => RespEncoder.WriteValue(new ArrayBufferWriter<byte>(), null!));
        Assert.Throws<ArgumentNullException>(() => RespEncoder.WriteValue(null!, RespNull.Instance));
    }
}
