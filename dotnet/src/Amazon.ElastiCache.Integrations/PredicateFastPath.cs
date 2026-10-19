using System.Text.Json;
using Antlr4.Runtime.Tree;
using Cel;
using Cel.Internal;

namespace Amazon.ElastiCache.Integrations;

/// <summary>
/// Evaluates a CEL predicate straight from the <c>Cel</c> parse tree and the request's
/// <see cref="JsonElement"/> body. It covers a subset of CEL and of value types, and answers only
/// where it gives the same result as <c>Cel</c> 0.3.3; everywhere else it defers to <c>Cel</c>.
/// </summary>
internal sealed class FastPredicate
{
    private static readonly Dictionary<string, object?> NoVariables = [];

    private readonly Node _root;
    private readonly int _slots;

    private FastPredicate(Node root, int slots)
    {
        _root = root;
        _slots = slots;
    }

    /// <summary>Null when the expression uses a construct this path does not cover.</summary>
    public static FastPredicate? TryCompile(CelEnvironment environment, CelParser.StartContext tree, IReadOnlyDictionary<string, object?> variables)
    {
        try
        {
            var compiler = new Compiler(environment, variables);
            return compiler.Compile(tree.e) is { } root ? new(root, compiler.Slots) : null;
        }
        catch (Exception)
        {
            // Cel alone still evaluates the predicate.
            return null;
        }
    }

    /// <summary>Whether the result is exactly <c>true</c>, or null when only <c>Cel</c> can tell.</summary>
    public bool? Matches(PredicateRequest request)
    {
        Value result;
        try
        {
            result = _root.Eval(request, _slots == 0 ? [] : new Value[_slots]);
        }
        catch (Exception)
        {
            return null;
        }

        return result.Kind == Kind.Defer ? null : result.Kind == Kind.Bool && result.Number == 1;
    }

    private sealed class Compiler(CelEnvironment environment, IReadOnlyDictionary<string, object?> variables)
    {
        private static readonly Dictionary<string, Func<PredicateRequest, Value>> Fields = new(StringComparer.Ordinal)
        {
            ["service"] = r => Value.Str(r.Request.Metadata.Service),
            ["operation"] = r => Value.Str(r.Request.Metadata.Operation),
            ["method"] = r => Value.Str(r.Request.Method),
            ["url"] = r => Value.Str(r.Request.Url),
            ["headers"] = r => Value.FromClr(r.Headers),
            ["body"] = r => Value.FromJson(r.Body),
        };

        private readonly List<(string Name, int Slot)> _scope = [];

        public int Slots { get; private set; }

        public Node? Compile(IParseTree? tree) => tree switch
        {
            CelParser.ExprContext { op: null } e => Compile(e.e),
            CelParser.ExprContext e => Compile(e.e) is { } c && Compile(e.e1) is { } a && Compile(e.e2) is { } b ? new Conditional(c, a, b) : null,
            CelParser.ConditionalOrContext o => o._e1.Count == 0 ? Compile(o.e) : Logic([o.e, .. o._e1], and: false),
            CelParser.ConditionalAndContext a => a._e1.Count == 0 ? Compile(a.e) : Logic([a.e, .. a._e1], and: true),
            CelParser.RelationContext { op: null } r => Compile(r.calc()),
            CelParser.RelationContext r => Compile(r.relation(0)) is { } a && Compile(r.relation(1)) is { } b ? new Relation(r.op.Text, a, b) : null,
            CelParser.CalcContext { op: null } c => Compile(c.unary()),
            CelParser.MemberExprContext m => Compile(m.member()),
            CelParser.LogicalNotContext n => Compile(n.member()) is { } operand ? new Not(operand, n._ops.Count % 2 == 1) : null,
            CelParser.PrimaryExprContext p => Compile(p.primary()),
            CelParser.SelectContext { opt: null } s => Select(s),
            CelParser.IndexContext { opt: null } i => Compile(i.member()) is { } m && Compile(i.index) is { } x ? new Index(m, x) : null,
            CelParser.MemberCallContext c => Macro(c),
            CelParser.IdentOrGlobalCallContext { leadingDot: null } g => g.exprList() is { } args ? Call(g.id.Text, args) : Identifier(g.id.Text),
            CelParser.NestedContext n => Compile(n.e),
            CelParser.ConstantLiteralContext l => Literal(l),
            _ => null,
        };

        private Logic? Logic(IParseTree[] operands, bool and)
        {
            var compiled = new Node[operands.Length];
            for (var i = 0; i < operands.Length; i++)
            {
                if (Compile(operands[i]) is not { } operand)
                {
                    return null;
                }

                compiled[i] = operand;
            }

            return new(compiled, and);
        }

        private Node? Select(CelParser.SelectContext select)
        {
            // Cel first looks up the whole dotted text as a variable.
            if (variables.TryGetValue(select.GetText(), out var variable))
            {
                return new Constant(Value.FromClr(variable));
            }

            // Cel resolves these prefixes as protobuf enum names.
            var member = select.member().GetText();
            if (string.IsNullOrEmpty(select.id?.Text) || member.StartsWith('.') || member.StartsWith("google.", StringComparison.Ordinal) || member.StartsWith("protobuf.", StringComparison.Ordinal))
            {
                return null;
            }

            return Compile(select.member()) is { } target ? new Select(target, select.id.Text) : null;
        }

        private Node? Identifier(string name)
        {
            for (var i = _scope.Count - 1; i >= 0; i--)
            {
                if (_scope[i].Name == name)
                {
                    return new Slot(_scope[i].Slot);
                }
            }

            if (variables.TryGetValue(name, out var variable))
            {
                return new Constant(Value.FromClr(variable));
            }

            return Fields.TryGetValue(name, out var read) ? new Field(read) : null;
        }

        private Node? Call(string name, CelParser.ExprListContext args)
        {
            if (args._e.Count != 1 || Compile(args._e[0]) is not { } argument)
            {
                return null;
            }

            return name switch
            {
                "has" => new Has(argument),
                "size" => new Size(argument),
                _ => null,
            };
        }

        private Node? Macro(CelParser.MemberCallContext call)
        {
            if (call.id?.Text != "all" || call.exprList() is not { _e.Count: 2 } args || !IsIdentifier(args._e[0].GetText()) || Compile(call.member()) is not { } target)
            {
                return null;
            }

            var slot = Slots++;
            _scope.Add((args._e[0].GetText(), slot));
            var body = Compile(args._e[1]);
            _scope.RemoveAt(_scope.Count - 1);
            return body is null ? null : new All(target, slot, body);
        }

        private Constant? Literal(CelParser.ConstantLiteralContext literal)
        {
            try
            {
                // Cel's own reading of the literal: escapes, quotes and number forms.
                return environment.Compile(literal.GetText())(NoVariables) is var value and (null or string or long or bool) ? new(Value.FromClr(value)) : null;
            }
            catch (Exception)
            {
                return null;
            }
        }

        private static bool IsIdentifier(string text) =>
            text.Length > 0 && (char.IsAsciiLetter(text[0]) || text[0] == '_') && text.All(c => char.IsAsciiLetterOrDigit(c) || c == '_');
    }

    private abstract class Node
    {
        public abstract Value Eval(PredicateRequest request, Value[] slots);
    }

    private sealed class Constant(Value value) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots) => value;
    }

    private sealed class Slot(int index) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots) => slots[index];
    }

    private sealed class Field(Func<PredicateRequest, Value> read) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots) => read(request);
    }

    private sealed class Select(Node member, string name) : Node
    {
        private readonly byte[] _utf8Name = System.Text.Encoding.UTF8.GetBytes(name);

        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = member.Eval(request, slots);
            return value.Kind switch
            {
                Kind.Null => Value.Null,
                Kind.Json when value.Json.ValueKind == JsonValueKind.Object =>
                    value.Json.TryGetProperty(_utf8Name, out var field) ? Value.FromJson(field) : Value.Missing,
                Kind.Map => ((Dictionary<string, object?>)value.Clr!).TryGetValue(name, out var item) ? Value.FromClr(item) : Value.Missing,
                _ => default,
            };
        }
    }

    private sealed class Index(Node member, Node index) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = member.Eval(request, slots);
            var key = index.Eval(request, slots);
            if (key.Kind == Kind.Long)
            {
                return value.Kind switch
                {
                    Kind.List when (List<object?>)value.Clr! is var list && key.Number >= 0 && key.Number < list.Count => Value.FromClr(list[(int)key.Number]),
                    Kind.Json when value.Json.ValueKind == JsonValueKind.Array && key.Number >= 0 && key.Number < value.Json.GetArrayLength() =>
                        Value.FromJson(value.Json[(int)key.Number]),
                    _ => default,
                };
            }

            if (!key.IsString || key.AsString() is not { Length: > 0 } name)
            {
                return default;
            }

            if (value.Kind == Kind.Json && value.Json.ValueKind == JsonValueKind.Object && value.Json.TryGetProperty(name, out var field))
            {
                return Value.FromJson(field);
            }

            return value.Kind == Kind.Map && ((Dictionary<string, object?>)value.Clr!).TryGetValue(name, out var item) ? Value.FromClr(item) : default;
        }
    }

    private sealed class Has(Node argument) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = argument.Eval(request, slots);
            return value.Kind switch
            {
                Kind.Defer => default,
                Kind.Null or Kind.Missing => Value.False,
                Kind.String => Value.Bool(((string)value.Clr!).Length > 0),
                Kind.Map => Value.Bool(((Dictionary<string, object?>)value.Clr!).Count > 0),
                Kind.List => Value.Bool(((List<object?>)value.Clr!).Count > 0),
                Kind.Json => Value.Bool(value.Json.ValueKind switch
                {
                    JsonValueKind.Object => value.Json.EnumerateObject().MoveNext(),
                    JsonValueKind.Array => value.Json.GetArrayLength() > 0,
                    _ => !value.Json.ValueEquals(""),
                }),
                _ => Value.True,
            };
        }
    }

    private sealed class Size(Node argument) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = argument.Eval(request, slots);
            return value.Kind switch
            {
                Kind.Map => Value.Int(((Dictionary<string, object?>)value.Clr!).Count),
                Kind.List => Value.Int(((List<object?>)value.Clr!).Count),
                Kind.Json when value.Json.ValueKind == JsonValueKind.Array => Value.Int(value.Json.GetArrayLength()),
                Kind.Json when value.Json.ValueKind == JsonValueKind.Object => Value.Int(CountNames(value.Json)),
                _ => default,
            };
        }

        // A repeated name counts once, as in the converted map.
        private static int CountNames(JsonElement value)
        {
            var count = 0;
            foreach (var _ in value.EnumerateObject())
            {
                count++;
            }

            if (count < 2)
            {
                return count;
            }

            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in value.EnumerateObject())
            {
                names.Add(property.Name);
            }

            return names.Count;
        }
    }

    private sealed class Not(Node operand, bool odd) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = operand.Eval(request, slots);
            return value.Kind switch
            {
                Kind.Bool => Value.Bool(odd ? value.Number == 0 : value.Number == 1),
                Kind.Null => Value.Null,
                _ => default,
            };
        }
    }

    private sealed class Logic(Node[] operands, bool and) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            // Left to right, stopping at the first operand that decides; Cel does the same.
            foreach (var operand in operands)
            {
                var value = operand.Eval(request, slots);
                if (value.Kind != Kind.Bool)
                {
                    return default;
                }

                if ((value.Number == 1) != and)
                {
                    return value;
                }
            }

            return Value.Bool(and);
        }
    }

    private sealed class Conditional(Node condition, Node then, Node otherwise) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = condition.Eval(request, slots);
            return value.Kind != Kind.Bool ? default : (value.Number == 1 ? then : otherwise).Eval(request, slots);
        }
    }

    private sealed class Relation(string op, Node left, Node right) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var a = left.Eval(request, slots);
            if (a.Kind is Kind.Defer or Kind.Missing)
            {
                return default;
            }

            var b = right.Eval(request, slots);
            if (b.Kind is Kind.Defer or Kind.Missing)
            {
                return default;
            }

            if (op == "in")
            {
                return In(a, b);
            }

            if (op is "==" or "!=")
            {
                return Equal(a, b) is { } equal ? Value.Bool(equal == (op == "==")) : default;
            }

            if (!a.IsInteger || !b.IsInteger)
            {
                return default;
            }

            return Value.Bool(op switch
            {
                "<" => a.Number < b.Number,
                "<=" => a.Number <= b.Number,
                ">" => a.Number > b.Number,
                _ => a.Number >= b.Number,
            });
        }

        private static bool? Equal(Value a, Value b)
        {
            if (a.Kind == Kind.Null || b.Kind == Kind.Null)
            {
                return a.Kind == b.Kind;
            }

            if (a.IsString && b.IsString)
            {
                return b.StringEquals(a.AsString());
            }

            if (a.IsInteger && b.IsInteger)
            {
                return a.Number == b.Number;
            }

            return a.Kind == Kind.Bool && b.Kind == Kind.Bool ? a.Number == b.Number : null;
        }

        private static Value In(Value item, Value container)
        {
            if (!item.IsString)
            {
                return default;
            }

            switch (container.Kind)
            {
                case Kind.List:
                    var text = item.AsString();
                    foreach (var element in (List<object?>)container.Clr!)
                    {
                        switch (element)
                        {
                            case string candidate when candidate == text:
                                return Value.True;
                            case string or null or long or double:
                                break;
                            default:
                                return default;
                        }
                    }

                    return Value.False;
                case Kind.Map:
                    return Value.Bool(item.AsString() is { Length: > 0 } key && ((Dictionary<string, object?>)container.Clr!).ContainsKey(key));
                case Kind.Json when container.Json.ValueKind == JsonValueKind.Object:
                    return Value.Bool(item.AsString() is { Length: > 0 } name && container.Json.TryGetProperty(name, out _));
                case Kind.Json when container.Json.ValueKind == JsonValueKind.Array:
                    var wanted = item.AsString();
                    foreach (var element in container.Json.EnumerateArray())
                    {
                        switch (element.ValueKind)
                        {
                            case JsonValueKind.String when element.ValueEquals(wanted):
                                return Value.True;
                            case JsonValueKind.String or JsonValueKind.Null or JsonValueKind.Number:
                                break;
                            default:
                                return default;
                        }
                    }

                    return Value.False;
                default:
                    return default;
            }
        }
    }

    private sealed class All(Node target, int slot, Node body) : Node
    {
        public override Value Eval(PredicateRequest request, Value[] slots)
        {
            var value = target.Eval(request, slots);
            switch (value.Kind)
            {
                case Kind.Json when value.Json.ValueKind == JsonValueKind.Array:
                    foreach (var element in value.Json.EnumerateArray())
                    {
                        slots[slot] = Value.FromJson(element);
                        if (Step(request, slots) is { } stop)
                        {
                            return stop;
                        }
                    }

                    return Value.True;

                // A repeated name is visited twice; the body sees the same value both times, so the result is the same.
                case Kind.Json when value.Json.ValueKind == JsonValueKind.Object:
                    foreach (var property in value.Json.EnumerateObject())
                    {
                        slots[slot] = Value.Str(property.Name);
                        if (Step(request, slots) is { } stop)
                        {
                            return stop;
                        }
                    }

                    return Value.True;
                case Kind.List:
                    foreach (var element in (List<object?>)value.Clr!)
                    {
                        slots[slot] = Value.FromClr(element);
                        if (Step(request, slots) is { } stop)
                        {
                            return stop;
                        }
                    }

                    return Value.True;
                case Kind.Map:
                    foreach (var key in ((Dictionary<string, object?>)value.Clr!).Keys)
                    {
                        slots[slot] = Value.Str(key);
                        if (Step(request, slots) is { } stop)
                        {
                            return stop;
                        }
                    }

                    return Value.True;
                default:
                    return default;
            }
        }

        // False or Defer ends the loop; true continues.
        private Value? Step(PredicateRequest request, Value[] slots)
        {
            var value = body.Eval(request, slots);
            return value.Kind == Kind.Bool && value.Number == 1 ? null : value.Kind == Kind.Bool ? value : default(Value);
        }
    }

    internal enum Kind : byte
    {
        /// <summary>Only <c>Cel</c> can tell; it may raise an error or follow a rule not modelled here.</summary>
        Defer,

        /// <summary>A map field that is not there: <c>Cel</c>'s <c>CelNoSuchField</c>.</summary>
        Missing,
        Null,
        Bool,
        Long,

        /// <summary>A <c>size()</c> result: <c>Cel</c>'s <c>int</c>, which it does not accept as an index.</summary>
        Int,
        Double,
        String,

        /// <summary>A body object, array or string.</summary>
        Json,
        Map,
        List,
    }

    internal readonly struct Value
    {
        public static readonly Value Missing = new(Kind.Missing);
        public static readonly Value Null = new(Kind.Null);
        public static readonly Value True = new(Kind.Bool, 1);
        public static readonly Value False = new(Kind.Bool, 0);

        public readonly Kind Kind;
        public readonly long Number;
        public readonly object? Clr;
        public readonly JsonElement Json;

        private Value(Kind kind, long number = 0, object? clr = null, JsonElement json = default)
        {
            Kind = kind;
            Number = number;
            Clr = clr;
            Json = json;
        }

        public static Value Bool(bool value) => value ? True : False;

        public static Value Long(long value) => new(Kind.Long, value);

        public static Value Int(int value) => new(Kind.Int, value);

        public static Value Str(string value) => new(Kind.String, clr: value);

        public static Value FromJson(JsonElement value) => value.ValueKind switch
        {
            JsonValueKind.Object or JsonValueKind.Array or JsonValueKind.String => new(Kind.Json, json: value),
            // The converted body holds every number as a double.
            JsonValueKind.Number => new(Kind.Double),
            JsonValueKind.True => True,
            JsonValueKind.False => False,
            _ => Null,
        };

        public static Value FromClr(object? value) => value switch
        {
            null => Null,
            bool boolean => Bool(boolean),
            long integer => Long(integer),
            double => new(Kind.Double),
            string text => Str(text),
            Dictionary<string, object?> map => new(Kind.Map, clr: map),
            List<object?> list => new(Kind.List, clr: list),
            _ => default,
        };

        public bool IsInteger => Kind is Kind.Long or Kind.Int;

        public bool IsString => Kind == Kind.String || (Kind == Kind.Json && Json.ValueKind == JsonValueKind.String);

        public string AsString() => Kind == Kind.String ? (string)Clr! : Json.GetString()!;

        public bool StringEquals(string other) => Kind == Kind.String ? (string)Clr! == other : Json.ValueEquals(other);
    }
}
