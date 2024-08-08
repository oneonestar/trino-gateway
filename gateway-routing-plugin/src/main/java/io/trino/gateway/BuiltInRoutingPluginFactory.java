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
package io.trino.gateway;

import io.trino.gateway.spi.routing.RoutingPlugin;
import io.trino.gateway.spi.routing.RoutingPluginFactory;

public class BuiltInRoutingPluginFactory
        implements RoutingPluginFactory<BuildInRoutingPluginConfig>
{
    @Override
    public String getName()
    {
        return "plugin1";
    }

    @Override
    public Class<BuildInRoutingPluginConfig> getConfigClass()
    {
        return BuildInRoutingPluginConfig.class;
    }

    @Override
    public RoutingPlugin create(BuildInRoutingPluginConfig config)
    {
        System.out.println(config);
        return null;
    }
}
