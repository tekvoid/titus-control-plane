/*
 * Copyright 2018 Netflix, Inc.
 *
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

package com.netflix.titus.master.mesos;

import javax.inject.Singleton;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.netflix.archaius.ConfigProxyFactory;
import com.netflix.runtime.health.guice.HealthModule;
import com.netflix.titus.master.VirtualMachineMasterService;
import com.netflix.titus.master.mesos.resolver.DefaultMesosMasterResolver;

public class MesosModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(MesosMasterResolver.class).to(DefaultMesosMasterResolver.class);
        bind(MesosSchedulerDriverFactory.class).to(StdSchedulerDriverFactory.class);
        bind(VirtualMachineMasterService.class).to(VirtualMachineMasterServiceMesosImpl.class);

        install(new HealthModule() {
            @Override
            protected void configureHealth() {
                bindAdditionalHealthIndicator().to(MesosHealthIndicator.class);
            }
        });
    }

    @Provides
    @Singleton
    public MesosConfiguration getMesosConfiguration(ConfigProxyFactory factory) {
        return factory.newProxy(MesosConfiguration.class);
    }
}