/*
 * Copyright (c) 2023 by Kang Wang. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.egolessness.destino.client.infrastructure;

import org.egolessness.destino.client.logging.DestinoLoggers;
import org.egolessness.destino.client.properties.DestinoProperties;
import org.egolessness.destino.common.balancer.RoundRobinBalancer;
import org.egolessness.destino.common.constant.CommonConstants;
import org.egolessness.destino.common.enumeration.RequestSchema;
import org.egolessness.destino.common.infrastructure.ListenableArrayList;
import org.egolessness.destino.common.model.HttpRequest;
import org.egolessness.destino.common.remote.RequestHighLevelClient;
import org.egolessness.destino.common.remote.http.DefaultBaseClient;
import org.egolessness.destino.common.support.RequestSupport;
import org.egolessness.destino.common.utils.PredicateUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ServerAddressesReader {

    private final Duration readInterval = Duration.ofSeconds(30);

    private final Duration requestTimeout = Duration.ofSeconds(10);

    private final DestinoProperties properties;

    private final DefaultBaseClient baseClient;

    private final RequestHighLevelClient requestClient;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private ScheduledExecutorService executor;

    private List<URI> cacheFromProviderUrl;

    public ServerAddressesReader(RequestHighLevelClient requestClient, DestinoProperties properties) {
        this.requestClient = requestClient;
        this.properties = properties;
        this.baseClient = new DefaultBaseClient(properties.getRequestProperties());
    }

    public void tryStart() {
        if (!isReadableForProviderUrl()) {
            return;
        }
        if (started.compareAndSet(false, true)) {
            executor = ExecutorCreator.createServerAddressesReaderExecutor();
            if (PredicateUtils.isEmpty(requestClient.getAddresses())) {
                this.read();
            } else {
                executor.execute(this::read);
            }
        }
    }

    private boolean isReadableForProviderUrl() {
        return PredicateUtils.isNotBlank(properties.getServersProviderUrl());
    }

    private void read() {
        try {
            String providerUrl = properties.getServersProviderUrl();
            if (PredicateUtils.isBlank(providerUrl)) {
                return;
            }
            if (!providerUrl.contains(CommonConstants.PROTOCOL_SIGN)) {
                providerUrl = RequestSchema.HTTP.getPrefix() + providerUrl;
            }
            URI uri = URI.create(providerUrl);
            HttpURLConnection connection = baseClient.connect(uri, new HttpRequest(), requestTimeout);
            InputStream responseStream = null;
            BufferedReader bufferedReader = null;
            try {
                InputStream errorStream = connection.getErrorStream();
                responseStream = errorStream != null ? errorStream : connection.getInputStream();
                bufferedReader = new BufferedReader(new InputStreamReader(responseStream));
                RequestSchema schema = RequestSchema.findByChannel(requestClient.channel(),
                        properties.getRequestProperties().getTlsProperties().isEnabled());
                List<URI> addresses = bufferedReader.lines()
                        .map(address -> RequestSupport.parseUri(address, schema))
                        .filter(Objects::nonNull).sorted()
                        .collect(Collectors.toList());
                if (!Objects.equals(cacheFromProviderUrl, addresses)) {
                    cacheFromProviderUrl = addresses;
                    refreshServerAddress();
                }
            } finally {
                try {
                    if (responseStream != null) responseStream.close();
                    if (bufferedReader != null) bufferedReader.close();
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            DestinoLoggers.REGISTRATION.warn("Failed to read servers from provider url: {}",
                    properties.getServersProviderUrl(), e);
        } finally {
            executor.schedule(this::read, readInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void refreshServerAddress() {
        List<URI> allUris = new ArrayList<>();

        ListenableArrayList<String> servers = properties.getServers();
        if (PredicateUtils.isNotEmpty(servers)) {
            RequestSchema schema = RequestSchema.findByChannel(requestClient.channel(),
                    properties.getRequestProperties().getTlsProperties().isEnabled());
            List<URI> propertiesUris = RequestSupport.parseUris(servers, schema);
            allUris.addAll(propertiesUris);
        }

        if (PredicateUtils.isNotEmpty(cacheFromProviderUrl)) {
            allUris.addAll(cacheFromProviderUrl);
        }

        requestClient.changeAddresses(new RoundRobinBalancer<>(allUris).convertPicker());
        requestClient.start();
    }

}
