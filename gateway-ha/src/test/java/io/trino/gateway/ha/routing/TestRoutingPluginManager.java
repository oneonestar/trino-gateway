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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.trino.gateway.ha.routing.TestRoutingPluginManager.PluginConfig;
import io.trino.gateway.spi.routing.RoutingPlugin;
import io.trino.gateway.spi.routing.RoutingPluginFactory;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;

class TestRoutingPluginManager
{
    String file = """
            hello: world
            foo_int: 123
            plugins:
             plugin1:
               foo: foo_value
               bar: [1, 2, 3]
               complex:
               - c1: c1_value
                 c2: c2_value
               - d1: d1_value
                 d2: d2_value
             plugin2:
               foo: foo_value2
               bar: ["4", "5", "6"]
               complex: diff_complex_content""";
    @Test
    public void test()
            throws JsonProcessingException
    {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        JsonNode obj = mapper.readTree(file);
        Iterator<Map.Entry<String, JsonNode>> plugins = obj.get("plugins").fields();

        plugins.forEachRemaining(plugin -> {
            System.out.println(plugin.getKey());
            System.out.println(plugin.getValue());
        });

        ServiceLoader<RoutingPluginFactory> loader = ServiceLoader.load(RoutingPluginFactory.class);
        for (RoutingPluginFactory f : loader) {
            System.out.println(f.getConfigClass());
//            Object config = mapper.treeToValue(obj.get("plugins").get("plugin1"), f.getConfigClass());
//            RoutingPlugin routingPlugin = f.create(config);
//            System.out.println(routingPlugin);


            PluginConfig pluginConfig = mapper.readValue(file, PluginConfig.class);
            System.out.println(pluginConfig);
            Object config = mapper.convertValue(pluginConfig.plugins, f.getConfigClass());
            RoutingPlugin routingPlugin = f.create(config);
            System.out.println(routingPlugin);
        }

    }

    public static class PluginConfig
    {
        public String hello;
        public int foo_int;
        public Map<String, Object> plugins;
    }
}
