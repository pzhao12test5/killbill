/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2017 Groupon, Inc
 * Copyright 2014-2017 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.entitlement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.entitlement.api.BlockingState;
import org.killbill.billing.entitlement.api.DefaultBlockingTransitionInternalEvent;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.entitlement.api.EntitlementApiException;
import org.killbill.billing.entitlement.dao.BlockingStateDao;
import org.killbill.billing.entitlement.engine.core.BlockingTransitionNotificationKey;
import org.killbill.billing.entitlement.engine.core.EntitlementNotificationKey;
import org.killbill.billing.entitlement.engine.core.EntitlementNotificationKeyAction;
import org.killbill.billing.entitlement.engine.core.EntitlementUtils;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.platform.api.LifecycleHandlerType;
import org.killbill.billing.platform.api.LifecycleHandlerType.LifecycleLevel;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.CallOrigin;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.callcontext.UserType;
import org.killbill.billing.util.listener.RetryException;
import org.killbill.billing.util.listener.RetryableHandler;
import org.killbill.billing.util.listener.RetryableService;
import org.killbill.bus.api.BusEvent;
import org.killbill.bus.api.PersistentBus;
import org.killbill.bus.api.PersistentBus.EventBusException;
import org.killbill.clock.Clock;
import org.killbill.notificationq.api.NotificationEvent;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService.NotificationQueueAlreadyExists;
import org.killbill.notificationq.api.NotificationQueueService.NotificationQueueHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

public class DefaultEntitlementService extends RetryableService implements EntitlementService {

    public static final String NOTIFICATION_QUEUE_NAME = "entitlement-events";

    private static final Logger log = LoggerFactory.getLogger(DefaultEntitlementService.class);

    private final Clock clock;
    private final EntitlementInternalApi entitlementInternalApi;
    private final BlockingStateDao blockingStateDao;
    private final PersistentBus eventBus;
    private final NotificationQueueService notificationQueueService;
    private final EntitlementUtils entitlementUtils;
    private final InternalCallContextFactory internalCallContextFactory;

    private NotificationQueue entitlementEventQueue;

    @Inject
    public DefaultEntitlementService(final Clock clock,
                                     final EntitlementInternalApi entitlementInternalApi,
                                     final BlockingStateDao blockingStateDao,
                                     final PersistentBus eventBus,
                                     final NotificationQueueService notificationQueueService,
                                     final EntitlementUtils entitlementUtils,
                                     final InternalCallContextFactory internalCallContextFactory) {
        super(notificationQueueService, internalCallContextFactory);
        this.clock = clock;
        this.entitlementInternalApi = entitlementInternalApi;
        this.blockingStateDao = blockingStateDao;
        this.eventBus = eventBus;
        this.notificationQueueService = notificationQueueService;
        this.entitlementUtils = entitlementUtils;
        this.internalCallContextFactory = internalCallContextFactory;
    }

    @Override
    public String getName() {
        return EntitlementService.ENTITLEMENT_SERVICE_NAME;
    }

    @LifecycleHandlerType(LifecycleLevel.INIT_SERVICE)
    public void initialize() {
        final NotificationQueueHandler queueHandler = new NotificationQueueHandler() {
            @Override
            public void handleReadyNotification(final NotificationEvent inputKey, final DateTime eventDateTime, final UUID fromNotificationQueueUserToken, final Long accountRecordId, final Long tenantRecordId) {
                final InternalCallContext internalCallContext = internalCallContextFactory.createInternalCallContext(tenantRecordId, accountRecordId, "EntitlementQueue", CallOrigin.INTERNAL, UserType.SYSTEM, fromNotificationQueueUserToken);

                try {
                    if (inputKey instanceof EntitlementNotificationKey) {
                        final CallContext callContext = internalCallContextFactory.createCallContext(internalCallContext);
                        processEntitlementNotification((EntitlementNotificationKey) inputKey, internalCallContext, callContext);
                    } else if (inputKey instanceof BlockingTransitionNotificationKey) {
                        processBlockingNotification((BlockingTransitionNotificationKey) inputKey, internalCallContext);
                    } else if (inputKey != null) {
                        log.error("Entitlement service received an unexpected event className='{}'", inputKey.getClass());
                        throw new RetryException();
                    } else {
                        log.error("Entitlement service received an unexpected null event");
                        // No retry
                    }
                } catch (final EntitlementApiException e) {
                    throw new RetryException(e);
                } catch (final EventBusException e) {
                    throw new RetryException(e);
                }
            }
        };

        try {
            final NotificationQueueHandler retryableHandler = new RetryableHandler(clock, this, queueHandler, internalCallContextFactory);
            entitlementEventQueue = notificationQueueService.createNotificationQueue(ENTITLEMENT_SERVICE_NAME,
                                                                                     NOTIFICATION_QUEUE_NAME,
                                                                                     retryableHandler);
        } catch (final NotificationQueueAlreadyExists e) {
            throw new RuntimeException(e);
        }

        super.initialize(entitlementEventQueue, queueHandler);
    }

    private void processEntitlementNotification(final EntitlementNotificationKey key, final InternalCallContext internalCallContext, final CallContext callContext) throws RetryException, EntitlementApiException {
        final Entitlement entitlement = entitlementInternalApi.getEntitlementForId(key.getEntitlementId(), internalCallContext);
        if (!(entitlement instanceof DefaultEntitlement)) {
            log.error("Error retrieving entitlementId='{}', unexpected entitlement className='{}'", key.getEntitlementId(), entitlement.getClass().getName());
            throw new RetryException();
        }

        final EntitlementNotificationKeyAction entitlementNotificationKeyAction = key.getEntitlementNotificationKeyAction();
        if (EntitlementNotificationKeyAction.CHANGE.equals(entitlementNotificationKeyAction) ||
            EntitlementNotificationKeyAction.CANCEL.equals(entitlementNotificationKeyAction)) {
            blockAddOnsIfRequired(key, (DefaultEntitlement) entitlement, callContext, internalCallContext);
        } else if (EntitlementNotificationKeyAction.PAUSE.equals(entitlementNotificationKeyAction)) {
            entitlementInternalApi.pause(key.getBundleId(), internalCallContext.toLocalDate(key.getEffectiveDate()), ImmutableList.<PluginProperty>of(), internalCallContext);
        } else if (EntitlementNotificationKeyAction.RESUME.equals(entitlementNotificationKeyAction)) {
            entitlementInternalApi.resume(key.getBundleId(), internalCallContext.toLocalDate(key.getEffectiveDate()), ImmutableList.<PluginProperty>of(), internalCallContext);
        }
    }

    private void blockAddOnsIfRequired(final EntitlementNotificationKey key, final DefaultEntitlement entitlement, final TenantContext callContext, final InternalCallContext internalCallContext) throws EntitlementApiException {
        final Collection<NotificationEvent> notificationEvents = new ArrayList<NotificationEvent>();
        final Collection<BlockingState> blockingStates = entitlement.computeAddOnBlockingStates(key.getEffectiveDate(), notificationEvents, callContext, internalCallContext);
        // Record the new state first, then insert the notifications to avoid race conditions
        entitlementUtils.setBlockingStatesAndPostBlockingTransitionEvent(blockingStates, entitlement.getBundleId(), internalCallContext);
        for (final NotificationEvent notificationEvent : notificationEvents) {
            recordFutureNotification(key.getEffectiveDate(), notificationEvent, internalCallContext);
        }
    }

    private void recordFutureNotification(final DateTime effectiveDate,
                                          final NotificationEvent notificationEvent,
                                          final InternalCallContext context) {
        try {
            final NotificationQueue subscriptionEventQueue = notificationQueueService.getNotificationQueue(DefaultEntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                                                           DefaultEntitlementService.NOTIFICATION_QUEUE_NAME);
            subscriptionEventQueue.recordFutureNotification(effectiveDate, notificationEvent, context.getUserToken(), context.getAccountRecordId(), context.getTenantRecordId());
        } catch (final NoSuchNotificationQueue e) {
            throw new RuntimeException(e);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void processBlockingNotification(final BlockingTransitionNotificationKey key, final InternalCallContext internalCallContext) throws EventBusException {
        // Check if the blocking state has been deleted since
        if (blockingStateDao.getById(key.getBlockingStateId(), internalCallContext) == null) {
            log.debug("BlockingState {} has been deleted, not sending a bus event", key.getBlockingStateId());
            return;
        }

        final BusEvent event = new DefaultBlockingTransitionInternalEvent(key.getBlockableId(),
                                                                          key.getStateName(),
                                                                          key.getService(),
                                                                          key.getEffectiveDate(),
                                                                          key.getBlockingType(),
                                                                          key.isTransitionedToBlockedBilling(),
                                                                          key.isTransitionedToUnblockedBilling(),
                                                                          key.isTransitionedToBlockedEntitlement(),
                                                                          key.isTransitionToUnblockedEntitlement(),
                                                                          internalCallContext.getAccountRecordId(),
                                                                          internalCallContext.getTenantRecordId(),
                                                                          internalCallContext.getUserToken());

        eventBus.post(event);
    }

    @LifecycleHandlerType(LifecycleLevel.START_SERVICE)
    public void start() {
        super.start();

        entitlementEventQueue.startQueue();
    }

    @LifecycleHandlerType(LifecycleLevel.STOP_SERVICE)
    public void stop() throws NoSuchNotificationQueue {
        if (entitlementEventQueue != null) {
            entitlementEventQueue.stopQueue();
            notificationQueueService.deleteNotificationQueue(entitlementEventQueue.getServiceName(), entitlementEventQueue.getQueueName());
        }

        super.stop();
    }
}
