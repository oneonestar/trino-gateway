/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.routing;

import com.google.inject.Inject;
import io.trino.gateway.spi.routing.RoutingPluginFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class RoutingPluginManager
{
    private final Map<String, RoutingPluginFactory> routingPluginFactories = new ConcurrentHashMap<>();
//    private final AtomicReference<List<RoutingPlugin>> configuredRoutingPlugins = new AtomicReference<>(ImmutableList.of());

    @Inject
    public RoutingPluginManager()
    {
    }

    public void addRoutingPluginFactory(RoutingPluginFactory routingPluginFactory)
    {
        requireNonNull(routingPluginFactory, "routingPluginFactory is null");

        if (routingPluginFactories.putIfAbsent(routingPluginFactory.getName(), routingPluginFactory) != null) {
            throw new IllegalArgumentException(format("Routing plugin factory '%s' is already registered", routingPluginFactory.getName()));
        }
    }

    public void loadPlugins()
    {
    }
}
