package com.grocery.shared.events;

import java.util.*;
import java.util.function.Consumer;

/** In-process, synchronous event bus between modules. In production: Spring Modulith's ApplicationEvents + event publication registry. */
public final class EventBus {
    private final Map<Class<?>, List<Consumer<Object>>> handlers = new HashMap<>();
    @SuppressWarnings("unchecked")
    public <E> void subscribe(Class<E> type, Consumer<E> handler) { handlers.computeIfAbsent(type, k -> new ArrayList<>()).add(e -> handler.accept((E) e)); }
    public void publish(Object event) { handlers.getOrDefault(event.getClass(), List.of()).forEach(h -> h.accept(event)); }
}
