// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.entitySystem.event.internal;

import com.esotericsoftware.reflectasm.MethodAccess;
import com.google.common.base.Objects;
import com.google.common.base.Predicates;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import org.reflections.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.entitySystem.Component;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.event.AbstractConsumableEvent;
import org.terasology.engine.entitySystem.event.ConsumableEvent;
import org.terasology.engine.entitySystem.event.Event;
import org.terasology.engine.entitySystem.event.EventPriority;
import org.terasology.engine.entitySystem.event.PendingEvent;
import org.terasology.engine.entitySystem.event.ReceiveEvent;
import org.terasology.engine.entitySystem.metadata.EventLibrary;
import org.terasology.engine.entitySystem.metadata.EventMetadata;
import org.terasology.engine.entitySystem.systems.ComponentSystem;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.network.BroadcastEvent;
import org.terasology.engine.network.Client;
import org.terasology.engine.network.NetworkComponent;
import org.terasology.engine.network.NetworkEvent;
import org.terasology.engine.network.NetworkMode;
import org.terasology.engine.network.NetworkSystem;
import org.terasology.engine.network.OwnerEvent;
import org.terasology.engine.network.ServerEvent;
import org.terasology.engine.recording.EventCatcher;
import org.terasology.engine.recording.RecordAndReplayCurrentStatus;
import org.terasology.engine.recording.RecordAndReplayStatus;
import org.terasology.engine.world.block.BlockComponent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

/**
 * An implementation of the EventSystem.
 *
 */
public class EventSystemImpl implements EventSystem {

    private static final Logger logger = LoggerFactory.getLogger(EventSystemImpl.class);

    private final Map<Class<? extends Event>, SetMultimap<Class<? extends Component>, EventHandlerInfo>> componentSpecificHandlers = Maps.newHashMap();
    private final SetMultimap<Class<? extends Event>, EventHandlerInfo> generalHandlers = HashMultimap.create();
    private final Comparator<EventHandlerInfo> priorityComparator = new EventHandlerPriorityComparator();

    // Event metadata
    private final BiMap<SimpleUri, Class<? extends Event>> eventIdMap = HashBiMap.create();
    private final SetMultimap<Class<? extends Event>, Class<? extends Event>> childEvents = HashMultimap.create();

    private final Thread mainThread;
    private final BlockingQueue<PendingEvent> pendingEvents = Queues.newLinkedBlockingQueue();

    private final EventLibrary eventLibrary;
    private final NetworkSystem networkSystem;
    private final EventCatcher eventCatcher;
    private final RecordAndReplayCurrentStatus recordAndReplayCurrentStatus;


    public EventSystemImpl(EventLibrary eventLibrary, NetworkSystem networkSystem, EventCatcher eventCatcher, RecordAndReplayCurrentStatus recordAndReplayCurrentStatus) {
        this.mainThread = Thread.currentThread();
        this.eventLibrary = eventLibrary;
        this.networkSystem = networkSystem;
        this.eventCatcher = eventCatcher;
        this.eventCatcher.startTimer();
        this.recordAndReplayCurrentStatus = recordAndReplayCurrentStatus;
    }

    @Override
    public void process() {
        for (PendingEvent event = pendingEvents.poll(); event != null; event = pendingEvents.poll()) {
            if (event.getComponent() != null) {
                send(event.getEntity(), event.getEvent(), event.getComponent());
            } else {
                send(event.getEntity(), event.getEvent());
            }
        }
    }

    @Override
    public void registerEvent(SimpleUri uri, Class<? extends Event> eventType) {
        eventIdMap.put(uri, eventType);
        logger.debug("Registering event {}", eventType.getSimpleName());
        for (Class parent : ReflectionUtils.getAllSuperTypes(eventType, Predicates.subtypeOf(Event.class))) {
            if (!AbstractConsumableEvent.class.equals(parent) && !Event.class.equals(parent)) {
                childEvents.put(parent, eventType);
            }
        }
        if (shouldAddToLibrary(eventType)) {
            eventLibrary.register(new ResourceUrn(uri.getModuleName(), uri.getObjectName()), eventType);
        }
    }

    /**
     * Events are added to the event library if they have a network annotation
     *
     * @param eventType the type of the event to be checked
     * @return Whether the event should be added to the event library
     */
    private boolean shouldAddToLibrary(Class<? extends Event> eventType) {
        return eventType.getAnnotation(ServerEvent.class) != null
                || eventType.getAnnotation(OwnerEvent.class) != null
                || eventType.getAnnotation(BroadcastEvent.class) != null;
    }

    @Override
    public void registerEventHandler(ComponentSystem handler) {
        Class handlerClass = handler.getClass();
        if (!Modifier.isPublic(handlerClass.getModifiers())) {
            logger.error("Cannot register handler {}, must be public", handler.getClass().getName());
            return;
        }

        logger.debug("Registering event handler " + handlerClass.getName());
        for (Method method : handlerClass.getMethods()) {
            ReceiveEvent receiveEventAnnotation = method.getAnnotation(ReceiveEvent.class);
            if (receiveEventAnnotation != null) {
                if (!receiveEventAnnotation.netFilter().isValidFor(networkSystem.getMode(), false)) {
                    continue;
                }
                Set<Class<? extends Component>> requiredComponents = Sets.newLinkedHashSet();
                method.setAccessible(true);
                Class<?>[] types = method.getParameterTypes();

                logger.debug("Found method: " + method.toString());
                if (!Event.class.isAssignableFrom(types[0]) || !EntityRef.class.isAssignableFrom(types[1])) {
                    logger.error("Invalid event handler method: {}", method.getName());
                    return;
                }

                requiredComponents.addAll(Arrays.asList(receiveEventAnnotation.components()));
                List<Class<? extends Component>> componentParams = Lists.newArrayList();
                for (int i = 2; i < types.length; ++i) {
                    if (!Component.class.isAssignableFrom(types[i])) {
                        logger.error("Invalid event handler method: {} - {} is not a component class", method.getName(), types[i]);
                        return;
                    }
                    requiredComponents.add((Class<? extends Component>) types[i]);
                    componentParams.add((Class<? extends Component>) types[i]);
                }

                ByteCodeEventHandlerInfo handlerInfo = new ByteCodeEventHandlerInfo(handler, method, receiveEventAnnotation.priority(),
                        receiveEventAnnotation.activity(), requiredComponents, componentParams);
                addEventHandler((Class<? extends Event>) types[0], handlerInfo, requiredComponents);
            }
        }
    }

    @Override
    public void unregisterEventHandler(ComponentSystem handler) {
        for (SetMultimap<Class<? extends Component>, EventHandlerInfo> eventHandlers : componentSpecificHandlers.values()) {
            Iterator<EventHandlerInfo> eventHandlerIterator = eventHandlers.values().iterator();
            while (eventHandlerIterator.hasNext()) {
                EventHandlerInfo eventHandler = eventHandlerIterator.next();
                if (eventHandler.getHandler().equals(handler)) {
                    eventHandlerIterator.remove();
                }
            }
        }

        Iterator<EventHandlerInfo> eventHandlerIterator = generalHandlers.values().iterator();
        while (eventHandlerIterator.hasNext()) {
            EventHandlerInfo eventHandler = eventHandlerIterator.next();
            if (eventHandler.getHandler().equals(handler)) {
                eventHandlerIterator.remove();
            }
        }
    }

    private void addEventHandler(Class<? extends Event> type, EventHandlerInfo handler, Collection<Class<? extends Component>> components) {
        if (components.isEmpty()) {
            generalHandlers.put(type, handler);
            for (Class<? extends Event> childType : childEvents.get(type)) {
                generalHandlers.put(childType, handler);
            }
        } else {
            for (Class<? extends Component> c : components) {
                addToComponentSpecificHandlers(type, handler, c);
                for (Class<? extends Event> childType : childEvents.get(type)) {
                    addToComponentSpecificHandlers(childType, handler, c);
                }
            }
        }
    }

    private void addToComponentSpecificHandlers(Class<? extends Event> type, EventHandlerInfo handlerInfo, Class<? extends Component> c) {
        SetMultimap<Class<? extends Component>, EventHandlerInfo> componentMap = componentSpecificHandlers.get(type);
        if (componentMap == null) {
            componentMap = HashMultimap.create();
            componentSpecificHandlers.put(type, componentMap);
        }
        componentMap.put(c, handlerInfo);
    }

    @Override
    public <T extends Event> void registerEventReceiver(EventReceiver<T> eventReceiver, Class<T> eventClass, Class<? extends Component>... componentTypes) {
        registerEventReceiver(eventReceiver, eventClass, EventPriority.PRIORITY_NORMAL, componentTypes);
    }

    @Override
    public <T extends Event> void registerEventReceiver(EventReceiver<T> eventReceiver, Class<T> eventClass, int priority, Class<? extends Component>... componentTypes) {
        EventHandlerInfo info = new ReceiverEventHandlerInfo<>(eventReceiver, priority, componentTypes);
        addEventHandler(eventClass, info, Arrays.asList(componentTypes));
    }

    @Override
    public <T extends Event> void unregisterEventReceiver(EventReceiver<T> eventReceiver, Class<T> eventClass, Class<? extends Component>... componentTypes) {
        SetMultimap<Class<? extends Component>, EventHandlerInfo> eventHandlerMap = componentSpecificHandlers.get(eventClass);
        if (eventHandlerMap != null) {
            ReceiverEventHandlerInfo testReceiver = new ReceiverEventHandlerInfo<>(eventReceiver, 0, componentTypes);
            for (Class<? extends Component> c : componentTypes) {
                eventHandlerMap.remove(c, testReceiver);
                for (Class<? extends Event> childType : childEvents.get(eventClass)) {
                    eventHandlerMap.remove(childType, testReceiver);
                }
            }
        }

        if (0 == componentTypes.length) {
            Iterator<EventHandlerInfo> eventHandlerIterator = generalHandlers.values().iterator();
            while (eventHandlerIterator.hasNext()) {
                EventHandlerInfo eventHandler = eventHandlerIterator.next();
                if (eventHandler.getHandler().equals(eventReceiver)) {
                    eventHandlerIterator.remove();
                }
            }
        }
    }

    @Override
    public void send(EntityRef entity, Event event) {
        if (Thread.currentThread() != mainThread) {
            pendingEvents.offer(new PendingEvent(entity, event));
        } else {
            if (recordAndReplayCurrentStatus.getStatus() == RecordAndReplayStatus.RECORDING) {
                eventCatcher.addEvent(new PendingEvent(entity, event));
            }
            networkReplicate(entity, event);

            Set<EventHandlerInfo> selectedHandlersSet = selectEventHandlers(event.getClass(), entity);
            List<EventHandlerInfo> selectedHandlers = Lists.newArrayList(selectedHandlersSet);
            selectedHandlers.sort(priorityComparator);

            if (event instanceof ConsumableEvent) {
                sendConsumableEvent(entity, event, selectedHandlers);
            } else {
                sendStandardEvent(entity, event, selectedHandlers);
            }
        }
    }

    private void sendStandardEvent(EntityRef entity, Event event, List<EventHandlerInfo> selectedHandlers) {
        for (EventHandlerInfo handler : selectedHandlers) {
            // Check isValid at each stage in case components were removed.
            if (handler.isValidFor(entity)) {
                handler.invoke(entity, event);
            }
        }
    }

    private void sendConsumableEvent(EntityRef entity, Event event, List<EventHandlerInfo> selectedHandlers) {
        ConsumableEvent consumableEvent = (ConsumableEvent) event;
        for (EventHandlerInfo handler : selectedHandlers) {
            // Check isValid at each stage in case components were removed.
            if (handler.isValidFor(entity)) {
                handler.invoke(entity, event);
                if (consumableEvent.isConsumed()) {
                    return;
                }
            }
        }
    }

    private void networkReplicate(EntityRef entity, Event event) {
        EventMetadata metadata = eventLibrary.getMetadata(event);
        if (metadata != null && metadata.isNetworkEvent()) {
            logger.debug("Replicating event: {}", event);
            switch (metadata.getNetworkEventType()) {
                case BROADCAST:
                    broadcastEvent(entity, event, metadata);
                    break;
                case OWNER:
                    sendEventToOwner(entity, event);
                    break;
                case SERVER:
                    sendEventToServer(entity, event);
                    break;
                default:
                    break;
            }
        }
    }

    private void sendEventToServer(EntityRef entity, Event event) {
        if (networkSystem.getMode() == NetworkMode.CLIENT) {
            NetworkComponent netComp = entity.getComponent(NetworkComponent.class);
            if (netComp != null) {
                networkSystem.getServer().send(event, entity);
            }
        }
    }

    private void sendEventToOwner(EntityRef entity, Event event) {
        if (networkSystem.getMode().isServer()) {
            NetworkComponent netComp = entity.getComponent(NetworkComponent.class);
            if (netComp != null) {
                Client client = networkSystem.getOwner(entity);
                if (client != null) {
                    client.send(event, entity);
                }
            }
        }
    }

    private void broadcastEvent(EntityRef entity, Event event, EventMetadata metadata) {
        if (networkSystem.getMode().isServer()) {
            NetworkComponent netComp = entity.getComponent(NetworkComponent.class);
            BlockComponent blockComp = entity.getComponent(BlockComponent.class);
            if (netComp != null || blockComp != null) {
                Client instigatorClient = null;
                if (metadata.isSkipInstigator() && event instanceof NetworkEvent) {
                    instigatorClient = networkSystem.getOwner(((NetworkEvent) event).getInstigator());
                }
                for (Client client : networkSystem.getPlayers()) {
                    if (!client.equals(instigatorClient)) {
                        client.send(event, entity);
                    }
                }
            }
        }
    }

    @Override
    public void send(EntityRef entity, Event event, Component component) {
        if (Thread.currentThread() != mainThread) {
            pendingEvents.offer(new PendingEvent(entity, event, component));
        } else {
            if (recordAndReplayCurrentStatus.getStatus() == RecordAndReplayStatus.RECORDING) {
                eventCatcher.addEvent(new PendingEvent(entity, event, component));
            }
            SetMultimap<Class<? extends Component>, EventHandlerInfo> handlers = componentSpecificHandlers.get(event.getClass());
            if (handlers != null) {
                List<EventHandlerInfo> eventHandlers = Lists.newArrayList(handlers.get(component.getClass()));
                eventHandlers.sort(priorityComparator);
                for (EventHandlerInfo eventHandler : eventHandlers) {
                    if (eventHandler.isValidFor(entity)) {
                        eventHandler.invoke(entity, event);
                    }
                }
            }
        }
    }

    private Set<EventHandlerInfo> selectEventHandlers(Class<? extends Event> eventType, EntityRef entity) {
        Set<EventHandlerInfo> result = Sets.newHashSet();
        result.addAll(generalHandlers.get(eventType));
        SetMultimap<Class<? extends Component>, EventHandlerInfo> handlers = componentSpecificHandlers.get(eventType);
        if (handlers == null) {
            return result;
        }

        for (Class<? extends Component> compClass : handlers.keySet()) {
            if (entity.hasComponent(compClass)) {
                for (EventHandlerInfo eventHandler : handlers.get(compClass)) {
                    if (eventHandler.isValidFor(entity)) {
                        result.add(eventHandler);
                    }
                }
            }
        }
        return result;
    }

    private static class EventHandlerPriorityComparator implements Comparator<EventHandlerInfo> {

        @Override
        public int compare(EventHandlerInfo o1, EventHandlerInfo o2) {
            return o2.getPriority() - o1.getPriority();
        }
    }

    private interface EventHandlerInfo {
        boolean isValidFor(EntityRef entity);

        void invoke(EntityRef entity, Event event);

        int getPriority();

        Object getHandler();
    }

    private static class ReflectedEventHandlerInfo implements EventHandlerInfo {
        private final ComponentSystem handler;
        private final Method method;
        private final ImmutableList<Class<? extends Component>> filterComponents;
        private final ImmutableList<Class<? extends Component>> componentParams;
        private final int priority;

        ReflectedEventHandlerInfo(ComponentSystem handler,
                                         Method method,
                                         int priority,
                                         Collection<Class<? extends Component>> filterComponents,
                                         Collection<Class<? extends Component>> componentParams) {
            this.handler = handler;
            this.method = method;
            this.filterComponents = ImmutableList.copyOf(filterComponents);
            this.componentParams = ImmutableList.copyOf(componentParams);
            this.priority = priority;
        }

        @Override
        public boolean isValidFor(EntityRef entity) {
            for (Class<? extends Component> component : filterComponents) {
                if (!entity.hasComponent(component)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void invoke(EntityRef entity, Event event) {
            try {
                Object[] params = new Object[2 + componentParams.size()];
                params[0] = event;
                params[1] = entity;
                for (int i = 0; i < componentParams.size(); ++i) {
                    params[i + 2] = entity.getComponent(componentParams.get(i));
                }
                method.invoke(handler, params);
            } catch (Exception ex) {
                logger.error("Failed to invoke event", ex);
            }
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public ComponentSystem getHandler() {
            return handler;
        }
    }

    private static class ByteCodeEventHandlerInfo implements EventHandlerInfo {
        private final ComponentSystem handler;
        private final String activity;
        private final MethodAccess methodAccess;
        private final int methodIndex;
        private final ImmutableList<Class<? extends Component>> filterComponents;
        private final ImmutableList<Class<? extends Component>> componentParams;
        private final int priority;

        ByteCodeEventHandlerInfo(ComponentSystem handler,
                                        Method method,
                                        int priority,
                                        String activity,
                                        Collection<Class<? extends Component>> filterComponents,
                                        Collection<Class<? extends Component>> componentParams) {


            this.handler = handler;
            this.activity = activity;
            this.methodAccess = MethodAccess.get(handler.getClass());
            methodIndex = methodAccess.getIndex(method.getName(), method.getParameterTypes());
            this.filterComponents = ImmutableList.copyOf(filterComponents);
            this.componentParams = ImmutableList.copyOf(componentParams);
            this.priority = priority;
        }

        @Override
        public boolean isValidFor(EntityRef entity) {
            for (Class<? extends Component> component : filterComponents) {
                if (!entity.hasComponent(component)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void invoke(EntityRef entity, Event event) {
            try {
                Object[] params = new Object[2 + componentParams.size()];
                params[0] = event;
                params[1] = entity;
                for (int i = 0; i < componentParams.size(); ++i) {
                    params[i + 2] = entity.getComponent(componentParams.get(i));
                }
                if (!activity.isEmpty()) {
                    PerformanceMonitor.startActivity(activity);
                }
                try {
                    methodAccess.invoke(handler, methodIndex, params);
                } finally {
                    if (!activity.isEmpty()) {
                        PerformanceMonitor.endActivity();
                    }
                }
            } catch (Exception ex) {
                logger.error("Failed to invoke event", ex);
            }
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public ComponentSystem getHandler() {
            return handler;
        }
    }

    private static class ReceiverEventHandlerInfo<T extends Event> implements EventHandlerInfo {
        private final EventReceiver<T> receiver;
        private final Class<? extends Component>[] components;
        private final int priority;

        ReceiverEventHandlerInfo(EventReceiver<T> receiver, int priority, Class<? extends Component>... components) {
            this.receiver = receiver;
            this.priority = priority;
            this.components = Arrays.copyOf(components, components.length);
        }

        @Override
        public boolean isValidFor(EntityRef entity) {
            for (Class<? extends Component> component : components) {
                if (!entity.hasComponent(component)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void invoke(EntityRef entity, Event event) {
            receiver.onEvent((T) event, entity);
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof ReceiverEventHandlerInfo) {
                ReceiverEventHandlerInfo other = (ReceiverEventHandlerInfo) obj;
                return Objects.equal(receiver, other.receiver);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(receiver);
        }

        @Override
        public Object getHandler() {
            return receiver;
        }
    }
}