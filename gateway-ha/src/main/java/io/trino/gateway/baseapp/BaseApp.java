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
package io.trino.gateway.baseapp;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import io.airlift.log.Logger;
import io.trino.gateway.ha.clustermonitor.ActiveClusterMonitor;
import io.trino.gateway.ha.clustermonitor.ClusterStatsHttpMonitor;
import io.trino.gateway.ha.clustermonitor.ClusterStatsInfoApiMonitor;
import io.trino.gateway.ha.clustermonitor.ClusterStatsJdbcMonitor;
import io.trino.gateway.ha.clustermonitor.ClusterStatsMonitor;
import io.trino.gateway.ha.clustermonitor.ClusterStatsObserver;
import io.trino.gateway.ha.clustermonitor.HealthCheckObserver;
import io.trino.gateway.ha.clustermonitor.NoopClusterStatsMonitor;
import io.trino.gateway.ha.clustermonitor.TrinoClusterStatsObserver;
import io.trino.gateway.ha.config.BackendStateConfiguration;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import io.trino.gateway.ha.config.MonitorConfiguration;
import io.trino.gateway.ha.config.RoutingStrategy;
import io.trino.gateway.ha.handler.ProxyHandlerStats;
import io.trino.gateway.ha.persistence.JdbcConnectionManager;
import io.trino.gateway.ha.persistence.RecordAndAnnotatedConstructorMapper;
import io.trino.gateway.ha.resource.EntityEditorResource;
import io.trino.gateway.ha.resource.GatewayResource;
import io.trino.gateway.ha.resource.GatewayViewResource;
import io.trino.gateway.ha.resource.GatewayWebAppResource;
import io.trino.gateway.ha.resource.HaGatewayResource;
import io.trino.gateway.ha.resource.LoginResource;
import io.trino.gateway.ha.resource.PublicResource;
import io.trino.gateway.ha.resource.TrinoResource;
import io.trino.gateway.ha.router.GatewayBackendManager;
import io.trino.gateway.ha.router.HaGatewayManager;
import io.trino.gateway.ha.router.HaQueryHistoryManager;
import io.trino.gateway.ha.router.HaResourceGroupsManager;
import io.trino.gateway.ha.router.QueryCountBasedRouter;
import io.trino.gateway.ha.router.QueryHistoryManager;
import io.trino.gateway.ha.router.ResourceGroupsManager;
import io.trino.gateway.ha.router.RoutingManager;
import io.trino.gateway.ha.router.StochasticRoutingManager;
import io.trino.gateway.ha.security.AuthorizedExceptionMapper;
import io.trino.gateway.proxyserver.ForProxy;
import io.trino.gateway.proxyserver.ProxyRequestHandler;
import io.trino.gateway.proxyserver.RouteToBackendResource;
import io.trino.gateway.proxyserver.RouterPreMatchContainerRequestFilter;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class BaseApp
        implements Module
{
    private static final Logger logger = Logger.get(BaseApp.class);
    private final HaGatewayConfiguration haGatewayConfiguration;

    public BaseApp(HaGatewayConfiguration haGatewayConfiguration)
    {
        this.haGatewayConfiguration = requireNonNull(haGatewayConfiguration);
    }

    private static Module newModule(String clazz, HaGatewayConfiguration configuration)
    {
        try {
            logger.info("Trying to load module [%s]", clazz);
            // Modules must have exactly one constructor. The signature must be:
            // public Module constructor(HaGatewayConfiguration)
            Constructor<?>[] constructors = Class.forName(clazz).getConstructors();
            if (constructors.length != 1) {
                throw new RuntimeException(format("Failed to load module [%s]. Multiple constructors exist.", clazz));
            }
            Constructor<?> constructor = constructors[0];
            if (constructor.getParameterCount() != 1) {
                throw new RuntimeException(format("Failed to load module [%s]. Unsupported constructor.", clazz));
            }
            Object module = constructor.newInstance(configuration);
            return ((Module) module);
        }
        catch (Exception e) {
            logger.error(e, "Could not instantiate module [%s]", clazz);
            System.exit(1);
        }
        return null;
    }

    public static List<Module> addModules(HaGatewayConfiguration configuration)
    {
        List<Module> modules = new ArrayList<>();
        if (configuration.getModules() == null) {
            logger.warn("No modules to load.");
            return modules;
        }
        for (String clazz : configuration.getModules()) {
            modules.add(newModule(clazz, configuration));
        }

        return modules;
    }

    @Override
    public void configure(Binder binder)
    {
        binder.bind(HaGatewayConfiguration.class).toInstance(haGatewayConfiguration);
        registerAuthFilters(binder);
        registerResources(binder);
        registerProxyResources(binder);
        registerRoutingModule(haGatewayConfiguration, binder);
        registerClusterStatsMonitorModule(haGatewayConfiguration, binder);
        addManagedApps(this.haGatewayConfiguration, binder);
        jaxrsBinder(binder).bind(AuthorizedExceptionMapper.class);
        binder.bind(ProxyHandlerStats.class).in(Scopes.SINGLETON);
        newExporter(binder).export(ProxyHandlerStats.class).withGeneratedName();
    }

    private static void addManagedApps(HaGatewayConfiguration configuration, Binder binder)
    {
        if (configuration.getManagedApps() == null) {
            logger.error("No managed apps found");
            return;
        }
        configuration.getManagedApps().forEach(
                clazz -> {
                    try {
                        Class c = Class.forName(clazz);
                        binder.bind(c).in(Scopes.SINGLETON);
                    }
                    catch (Exception e) {
                        logger.error(e, "Error loading managed app");
                    }
                });
    }

    private static void registerResources(Binder binder)
    {
        jaxrsBinder(binder).bind(EntityEditorResource.class);
        jaxrsBinder(binder).bind(GatewayResource.class);
        jaxrsBinder(binder).bind(GatewayViewResource.class);
        jaxrsBinder(binder).bind(GatewayWebAppResource.class);
        jaxrsBinder(binder).bind(HaGatewayResource.class);
        jaxrsBinder(binder).bind(LoginResource.class);
        jaxrsBinder(binder).bind(PublicResource.class);
        jaxrsBinder(binder).bind(TrinoResource.class);
        jaxrsBinder(binder).bind(WebUIStaticResource.class);
    }

    private static void registerAuthFilters(Binder binder)
    {
        jaxrsBinder(binder).bind(RolesAllowedDynamicFeature.class);
    }

    private static void registerProxyResources(Binder binder)
    {
        jaxrsBinder(binder).bind(RouteToBackendResource.class);
        jaxrsBinder(binder).bind(RouterPreMatchContainerRequestFilter.class);
        jaxrsBinder(binder).bind(ProxyRequestHandler.class);
        httpClientBinder(binder).bindHttpClient("proxy", ForProxy.class);
    }

    private static void registerRoutingModule(HaGatewayConfiguration configuration, Binder binder)
    {
        Jdbi jdbi = Jdbi.create(
                configuration.getDataStore().getJdbcUrl(),
                configuration.getDataStore().getUser(),
                configuration.getDataStore().getPassword())
                .installPlugin(new SqlObjectPlugin())
                .registerRowMapper(new RecordAndAnnotatedConstructorMapper());
        binder.bind(Jdbi.class).toInstance(jdbi);
        binder.bind(JdbcConnectionManager.class).in(Scopes.SINGLETON);
        binder.bind(QueryHistoryManager.class).to(HaQueryHistoryManager.class).in(Scopes.SINGLETON);
        binder.bind(GatewayBackendManager.class).to(HaGatewayManager.class).in(Scopes.SINGLETON);
        binder.bind(ResourceGroupsManager.class).to(HaResourceGroupsManager.class).in(Scopes.SINGLETON);
        RoutingStrategy routingStrategy = configuration.getRoutingStrategy();
        if (routingStrategy == null) {
            binder.bind(RoutingManager.class).to(StochasticRoutingManager.class).in(Scopes.SINGLETON);
        }
        else {
            switch (routingStrategy) {
                case StochasticRouting ->
                        binder.bind(RoutingManager.class).to(StochasticRoutingManager.class).in(Scopes.SINGLETON);
                case QueryCountBasedRouting ->
                        binder.bind(RoutingManager.class).to(QueryCountBasedRouter.class).in(Scopes.SINGLETON);
            }
        }
    }

    private static void registerClusterStatsMonitorModule(HaGatewayConfiguration config, Binder binder)
    {
        MonitorConfiguration monitorConfig = config.getMonitor();
        if (monitorConfig == null) {
            binder.bind(ClusterStatsMonitor.class).to(ClusterStatsInfoApiMonitor.class).in(Scopes.SINGLETON);
            binder.bind(MonitorConfiguration.class).in(Scopes.SINGLETON);
        }
        else {
            switch (monitorConfig.getMonitorType()) {
                case INFO_API -> binder.bind(ClusterStatsMonitor.class).to(ClusterStatsInfoApiMonitor.class).in(Scopes.SINGLETON);
                case NOOP -> binder.bind(ClusterStatsMonitor.class).to(NoopClusterStatsMonitor.class).in(Scopes.SINGLETON);
                case UI_API -> {
                    bindBackendStateConfiguration(config, binder);
                    binder.bind(ClusterStatsMonitor.class).to(ClusterStatsHttpMonitor.class).in(Scopes.SINGLETON);
                }
                case JDBC -> {
                    bindBackendStateConfiguration(config, binder);
                    binder.bind(ClusterStatsMonitor.class).to(ClusterStatsJdbcMonitor.class).in(Scopes.SINGLETON);
                }
            }
            binder.bind(MonitorConfiguration.class).toInstance(monitorConfig);
        }
        Multibinder<TrinoClusterStatsObserver> observerMultibinder = Multibinder.newSetBinder(binder, TrinoClusterStatsObserver.class);
        observerMultibinder.addBinding().to(HealthCheckObserver.class);
        observerMultibinder.addBinding().to(ClusterStatsObserver.class);
        binder.bind(ActiveClusterMonitor.class).in(Scopes.SINGLETON);
    }

    private static void bindBackendStateConfiguration(HaGatewayConfiguration config, Binder binder)
    {
        BackendStateConfiguration backendState = config.getBackendState();
        if (backendState == null) {
            throw new RuntimeException("Backend state is required for UI_API/JDBC monitoring");
        }
        binder.bind(BackendStateConfiguration.class).toInstance(backendState);
    }
}
