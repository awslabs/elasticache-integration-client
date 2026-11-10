using System.Runtime.CompilerServices;

namespace Amazon.ElastiCache.Integrations.Tests;

/// <summary>Keeps the AWS SDK off the host's <c>~/.aws</c> files, environment and instance metadata in every test.</summary>
internal static class SdkEnvironment
{
    [ModuleInitializer]
    internal static void Isolate()
    {
        var empty = Path.Combine(Path.GetTempPath(), $"eci-tests-empty-{Environment.ProcessId}.ini");
        File.WriteAllText(empty, string.Empty);
        AppDomain.CurrentDomain.ProcessExit += (_, _) => File.Delete(empty);
        Environment.SetEnvironmentVariable("AWS_CONFIG_FILE", empty);
        Environment.SetEnvironmentVariable("AWS_SHARED_CREDENTIALS_FILE", empty);
        Environment.SetEnvironmentVariable("AWS_EC2_METADATA_DISABLED", "true");
        foreach (var name in new[] { "AWS_PROFILE", "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_DYNAMODB" })
        {
            Environment.SetEnvironmentVariable(name, null);
        }
    }
}
