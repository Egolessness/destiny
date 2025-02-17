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

package org.egolessness.destino.scheduler.model;

import org.egolessness.destino.common.enumeration.Mark;
import org.egolessness.destino.common.enumeration.RequestChannel;
import org.egolessness.destino.common.model.ServiceInstance;
import org.egolessness.destino.common.support.RequestSupport;
import org.egolessness.destino.common.utils.NumberUtils;
import org.egolessness.destino.common.utils.PredicateUtils;
import org.egolessness.destino.registration.support.RegistrationSupport;
import org.egolessness.destino.registration.message.RegistrationKey;
import org.egolessness.destino.registration.model.event.InstanceChangedEvent;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Objects;

/**
 * instance packing.
 *
 * @author zsmjwk@outlook.com (wangkang)
 */
public class InstancePacking implements Comparable<InstancePacking>, Serializable {

    private static final long serialVersionUID = 8902219386151257612L;

    private final ServiceInstance instance;

    private final RegistrationKey registrationKey;

    private final long registerTime;

    private final long sourceId;

    private final String connectionId;

    private final RequestChannel channel;

    private volatile boolean removed;

    private long lastConnectFailedTime;

    public InstancePacking(InstanceChangedEvent event) {
        this.instance = event.getInstance();
        this.registrationKey = event.getRegistrationKey();
        this.registerTime = event.getRegisterTime();
        this.sourceId = event.getSourceId();
        this.channel = event.getChannel();
        this.connectionId = event.getConnectionId();
    }

    public ServiceInstance getInstance() {
        return instance;
    }

    public RegistrationKey getRegistrationKey() {
        return registrationKey;
    }

    public long getRegisterTime() {
        return registerTime;
    }

    public long getSourceId() {
        return sourceId;
    }

    public RequestChannel getChannel() {
        return channel;
    }

    public int getUdpPort() {
        return instance.getUdpPort();
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public boolean isConnectable() {
        return System.currentTimeMillis() - lastConnectFailedTime > 20000;
    }

    public void connectFailed() {
        this.lastConnectFailedTime = System.currentTimeMillis();
    }

    public boolean udpAvailable() {
        return RegistrationSupport.validatePort(instance.getUdpPort());
    }

    public String getConnectionId() {
        return connectionId;
    }

    public long getConnectedServerId() {
        if (PredicateUtils.isEmpty(connectionId)) {
            return -1;
        }
        return NumberUtils.parseLong(Mark.UNDERLINE.split(connectionId)[0], -1);
    }

    public boolean isReachable(long memberId) {
        if (PredicateUtils.isNotEmpty(connectionId) && connectionId.startsWith(memberId + Mark.UNDERLINE.getValue())) {
            return true;
        }
        return !RequestSupport.isSupportRequestStreamReceiver(channel) && udpAvailable();
    }

    @Override
    public int compareTo(@Nonnull InstancePacking next) {
        int compare = Long.compare(registerTime, next.registerTime);
        if (compare != 0) {
            return compare;
        }
        return registrationKey.toString().compareTo(next.registrationKey.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InstancePacking that = (InstancePacking) o;
        return Objects.equals(registrationKey, that.registrationKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registrationKey);
    }

    @Override
    public String toString() {
        return "InstancePacking{" +
                "instance=" + instance +
                ", registrationKey=" + registrationKey +
                ", registerTime=" + registerTime +
                '}';
    }
}