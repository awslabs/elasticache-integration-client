// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.examples;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Resolves {@code ELASTICACHE_CACHE_HOST} to {@code ELASTICACHE_TUNNEL_HOST} and every
 * other name with the JDK's built-in resolver.
 *
 * <p>This matches the Node and Python examples: TCP goes to the local tunnel,
 * while TLS still verifies the cache hostname because the client connects by
 * that name. Credential and DynamoDB endpoints keep resolving normally. The
 * JDK loads this provider through {@code META-INF/services}; it requires
 * JDK 18 or later.
 */
public final class TunnelResolverProvider extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        String cacheHost = System.getenv("ELASTICACHE_CACHE_HOST");
        String tunnelHost = System.getenv("ELASTICACHE_TUNNEL_HOST");
        if (cacheHost == null || cacheHost.isEmpty()
                || tunnelHost == null || tunnelHost.isEmpty()) {
            return builtin;
        }
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                String target = host.equalsIgnoreCase(cacheHost) ? tunnelHost : host;
                return builtin.lookupByName(target, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "elasticache-integrations-e2e-tunnel";
    }
}
