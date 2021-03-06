/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.clustering.infinispan.subsystem.remote;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.infinispan.client.hotrod.ProtocolVersion;
import org.infinispan.client.hotrod.configuration.ClusterConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.ConnectionPoolConfiguration;
import org.infinispan.client.hotrod.configuration.ExecutorFactoryConfiguration;
import org.infinispan.client.hotrod.configuration.NearCacheConfiguration;
import org.infinispan.client.hotrod.configuration.SecurityConfiguration;
import org.jboss.as.clustering.controller.CapabilityServiceNameProvider;
import org.jboss.as.clustering.controller.CommonUnaryRequirement;
import org.jboss.as.clustering.controller.ResourceServiceBuilder;
import org.jboss.as.clustering.infinispan.subsystem.ThreadPoolResourceDefinition;
import org.jboss.as.clustering.infinispan.subsystem.remote.RemoteCacheContainerResourceDefinition.Attribute;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.StringListAttributeDefinition;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.network.OutboundSocketBinding;
import org.jboss.dmr.ModelNode;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.ValueService;
import org.jboss.msc.value.Value;
import org.wildfly.clustering.service.Builder;
import org.wildfly.clustering.service.CompositeDependency;
import org.wildfly.clustering.service.Dependency;
import org.wildfly.clustering.service.InjectedValueDependency;
import org.wildfly.clustering.service.ValueDependency;

/**
 * @author Radoslav Husar
 */
public class RemoteCacheContainerConfigurationBuilder extends CapabilityServiceNameProvider implements ResourceServiceBuilder<Configuration>, Value<Configuration> {

    private final Map<String, List<ValueDependency<OutboundSocketBinding>>> clusters = new HashMap<>();
    private final Map<ThreadPoolResourceDefinition, ValueDependency<ExecutorFactoryConfiguration>> threadPools = new EnumMap<>(ThreadPoolResourceDefinition.class);
    private final ValueDependency<Module> module;
    private final ValueDependency<ConnectionPoolConfiguration> connectionPool;
    private final ValueDependency<NearCacheConfiguration> nearCache;
    private final ValueDependency<SecurityConfiguration> security;

    private volatile int connectionTimeout;
    private volatile String defaultRemoteCluster;
    private volatile int keySizeEstimate;
    private volatile int maxRetries;
    private volatile String protocolVersion;
    private volatile int socketTimeout;
    private volatile boolean tcpNoDelay;
    private volatile boolean tcpKeepAlive;
    private volatile int valueSizeEstimate;

    RemoteCacheContainerConfigurationBuilder(PathAddress address) {
        super(RemoteCacheContainerResourceDefinition.Capability.CONFIGURATION, address);
        this.threadPools.put(ThreadPoolResourceDefinition.CLIENT, new InjectedValueDependency<>(ThreadPoolResourceDefinition.CLIENT.getServiceName(address), ExecutorFactoryConfiguration.class));
        this.module = new InjectedValueDependency<>(RemoteCacheContainerComponent.MODULE.getServiceName(address), Module.class);
        this.connectionPool = new InjectedValueDependency<>(RemoteCacheContainerComponent.CONNECTION_POOL.getServiceName(address), ConnectionPoolConfiguration.class);
        this.nearCache = new InjectedValueDependency<>(RemoteCacheContainerComponent.NEAR_CACHE.getServiceName(address), NearCacheConfiguration.class);
        this.security = new InjectedValueDependency<>(RemoteCacheContainerComponent.SECURITY.getServiceName(address), SecurityConfiguration.class);
    }

    @Override
    public Builder<Configuration> configure(OperationContext context, ModelNode model) throws OperationFailedException {
        this.connectionTimeout = Attribute.CONNECTION_TIMEOUT.resolveModelAttribute(context, model).asInt();
        this.defaultRemoteCluster = Attribute.DEFAULT_REMOTE_CLUSTER.resolveModelAttribute(context, model).asString();
        this.keySizeEstimate = Attribute.KEY_SIZE_ESTIMATE.resolveModelAttribute(context, model).asInt();
        this.maxRetries = Attribute.MAX_RETRIES.resolveModelAttribute(context, model).asInt();
        this.protocolVersion = Attribute.PROTOCOL_VERSION.resolveModelAttribute(context, model).asString();
        this.socketTimeout = Attribute.SOCKET_TIMEOUT.resolveModelAttribute(context, model).asInt();
        this.tcpNoDelay = Attribute.TCP_NO_DELAY.resolveModelAttribute(context, model).asBoolean();
        this.tcpKeepAlive = Attribute.TCP_KEEP_ALIVE.resolveModelAttribute(context, model).asBoolean();
        this.valueSizeEstimate = Attribute.VALUE_SIZE_ESTIMATE.resolveModelAttribute(context, model).asInt();

        this.clusters.clear();

        Resource container = context.readResource(PathAddress.EMPTY_ADDRESS);
        for (Resource.ResourceEntry entry : container.getChildren(RemoteClusterResourceDefinition.WILDCARD_PATH.getKey())) {
            String clusterName = entry.getName();
            ModelNode cluster = entry.getModel();
            List<String> bindings = StringListAttributeDefinition.unwrapValue(context, RemoteClusterResourceDefinition.Attribute.SOCKET_BINDINGS.resolveModelAttribute(context, cluster));
            List<ValueDependency<OutboundSocketBinding>> bindingDependencies = new ArrayList<>(bindings.size());
            for (String binding : bindings) {
                bindingDependencies.add(new InjectedValueDependency<>(CommonUnaryRequirement.OUTBOUND_SOCKET_BINDING.getServiceName(context, binding), OutboundSocketBinding.class));
            }
            this.clusters.put(clusterName, bindingDependencies);
        }

        return this;
    }

    @Override
    public ServiceBuilder<Configuration> build(ServiceTarget target) {
        ServiceBuilder<Configuration> builder = target.addService(this.getServiceName(), new ValueService<>(this)).setInitialMode(ServiceController.Mode.ON_DEMAND);
        for (Dependency dependency : this.threadPools.values()) {
            dependency.register(builder);
        }
        for (List<ValueDependency<OutboundSocketBinding>> dependencies : this.clusters.values()) {
            for (Dependency dependency : dependencies) {
                dependency.register(builder);
            }
        }
        return new CompositeDependency(this.module, this.connectionPool, this.nearCache, this.security).register(builder);
    }

    @Override
    public Configuration getValue() {
        ConfigurationBuilder builder = new ConfigurationBuilder()
                .marshaller(new HotRodMarshaller(this.module.getValue()))
                .connectionTimeout(this.connectionTimeout)
                .keySizeEstimate(this.keySizeEstimate)
                .maxRetries(this.maxRetries)
                .version(ProtocolVersion.parseVersion(this.protocolVersion))
                .socketTimeout(this.socketTimeout)
                .tcpNoDelay(this.tcpNoDelay)
                .tcpKeepAlive(this.tcpKeepAlive)
                .valueSizeEstimate(this.valueSizeEstimate);

        builder.connectionPool().read(this.connectionPool.getValue());
        builder.nearCache().read(this.nearCache.getValue());
        builder.asyncExecutorFactory().read(this.threadPools.get(ThreadPoolResourceDefinition.CLIENT).getValue());

        for (Map.Entry<String, List<ValueDependency<OutboundSocketBinding>>> cluster : this.clusters.entrySet()) {
            String clusterName = cluster.getKey();
            List<ValueDependency<OutboundSocketBinding>> bindingDependencies = cluster.getValue();

            if (this.defaultRemoteCluster.equals(clusterName)) {
                for (Value<OutboundSocketBinding> bindingDependency : bindingDependencies) {
                    OutboundSocketBinding binding = bindingDependency.getValue();
                    builder.addServer().host(binding.getUnresolvedDestinationAddress()).port(binding.getDestinationPort());
                }
            } else {
                ClusterConfigurationBuilder clusterConfigurationBuilder = builder.addCluster(clusterName);
                for (Value<OutboundSocketBinding> bindingDependency : bindingDependencies) {
                    OutboundSocketBinding binding = bindingDependency.getValue();
                    clusterConfigurationBuilder.addClusterNode(binding.getUnresolvedDestinationAddress(), binding.getDestinationPort());
                }
            }
        }

        builder.security().read(this.security.getValue());

        return builder.build();
    }
}
