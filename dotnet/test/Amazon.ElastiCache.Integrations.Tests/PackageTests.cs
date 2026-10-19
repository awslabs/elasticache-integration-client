using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.Versioning;

namespace Amazon.ElastiCache.Integrations.Tests;

public class PackageTests
{
    private static readonly Assembly Library = Assembly.Load("Amazon.ElastiCache.Integrations");

    [Fact]
    public void LibraryTargetsNet8()
    {
        var framework = Library.GetCustomAttribute<TargetFrameworkAttribute>();

        Assert.Equal(".NETCoreApp,Version=v8.0", framework?.FrameworkName);
    }

    [Fact]
    public void InternalsAreVisibleOnlyToTestsAndBenchmarks()
    {
        var friends = Library.GetCustomAttributes<InternalsVisibleToAttribute>()
            .Select(a => a.AssemblyName);

        Assert.Equal(["Amazon.ElastiCache.Integrations.Tests", "Amazon.ElastiCache.Integrations.Benchmarks"], friends);
    }
}
