package org.openhab.io.yandexalice.internal;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.GroupFunction;
import org.openhab.core.items.GroupItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemBuilder;
import org.openhab.core.items.ItemBuilderFactory;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.TypeParser;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mirrors Yandex-tagged Items from another openHAB without a scripting add-on. */
public final class RemoteMirrorService implements EventSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteMirrorService.class);
    private static final String EVENT_SOURCE = "yandex-remote-mirror";
    private final Object stateLock = new Object();
    private final Object statusLock = new Object();
    private final Map<String, Long> eventTimes = new ConcurrentHashMap<>();
    private ManagedItemProvider managedItems;
    private ItemBuilderFactory itemBuilders;
    private ItemRegistry itemRegistry;
    private EventPublisher eventPublisher;
    private ConfigurationAdmin configurationAdmin;
    private volatile List<String> connectionSettings;
    private volatile RemoteMirrorHttp remoteHttp;
    private volatile Map<String, RemoteMirrorPlanner.Spec> active = Map.of();
    private volatile String prefix = "alice_remote_";
    private volatile String tag = "Yandex";
    private volatile String lastError = "";
    private volatile ScheduledExecutorService executor;

    public RemoteMirrorService() {
    }

    protected void bindManagedItems(ManagedItemProvider managedItems) {
        this.managedItems = managedItems;
    }

    protected void bindItemBuilders(ItemBuilderFactory itemBuilders) {
        this.itemBuilders = itemBuilders;
    }

    protected void bindItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    protected void bindEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    protected void bindConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    protected synchronized void activate(Map<String, ?> config) {
        modified(config);
    }

    protected synchronized void modified(Map<String, ?> config) {
        String remoteUrl = value(config, "remoteUrl", "").strip();
        String token = value(config, "remoteToken", "");
        List<String> settings = List.of(remoteUrl, token, value(config, "remoteTag", "Yandex"),
                value(config, "remotePrefix", "alice_remote_"), value(config, "remotePollSeconds", "10"));
        boolean connectionChanged = !settings.equals(connectionSettings);
        connectionSettings = settings;
        publishStatus("callbackDiagnostic",
                RemoteMirrorStatus.callbacks(value(config, "skillID", ""), value(config, "oauth", "")));
        if (!connectionChanged)
            return;
        stopExecutor();
        active = Map.of();
        if (remoteUrl.isEmpty()) {
            remoteHttp = null;
            publishStatus("bridgeDiagnostic", RemoteMirrorStatus.noAddress());
            publishStatus("eventsDiagnostic", "ℹ Поток событий на этом сервере не используется.");
            LOGGER.info("Yandex remote Item bridge is disabled; configure Remote openHAB URL to enable it");
            return;
        }
        try {
            prefix = value(config, "remotePrefix", "alice_remote_").strip();
            tag = value(config, "remoteTag", "Yandex").strip();
            if (!prefix.matches("[A-Za-z][A-Za-z0-9_]*") || tag.isEmpty()) {
                throw new IllegalArgumentException("Invalid remote Item prefix or tag");
            }
            int interval = Integer.parseInt(value(config, "remotePollSeconds", "10"));
            if (interval < 5 || interval > 3600) {
                throw new IllegalArgumentException("Remote poll interval must be 5..3600 seconds");
            }
            remoteHttp = new RemoteMirrorHttp(remoteUrl, token);
            publishStatus("bridgeDiagnostic", RemoteMirrorStatus.checking());
            publishStatus("eventsDiagnostic", "⌛ Подключаем поток событий второго сервера...");
            executor = Executors.newScheduledThreadPool(3, task -> {
                Thread thread = new Thread(task, "yandex-remote-items");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::pollSafely, 0, interval, TimeUnit.SECONDS);
            RemoteMirrorHttp eventsHttp = remoteHttp;
            executor.execute(() -> eventLoop(eventsHttp));
            LOGGER.info("Yandex remote Item bridge enabled for {}", remoteUrl);
        } catch (IllegalArgumentException error) {
            remoteHttp = null;
            publishStatus("bridgeDiagnostic", RemoteMirrorStatus.failure(error));
            publishStatus("eventsDiagnostic", "⚠ Поток событий выключен из-за ошибки настройки.");
            LOGGER.error("Yandex remote Item bridge configuration error: {}", error.getMessage());
        }
    }

    protected synchronized void deactivate() {
        stopExecutor();
        remoteHttp = null;
        active = Map.of();
    }

    private void stopExecutor() {
        ScheduledExecutorService old = executor;
        executor = null;
        if (old != null)
            old.shutdownNow();
    }

    private void publishStatus(String key, String message) {
        ConfigurationAdmin admin = configurationAdmin;
        if (admin == null)
            return;
        synchronized (statusLock) {
            try {
                org.osgi.service.cm.Configuration configuration = admin.getConfiguration("org.openhab.yandexalice",
                        null);
                Dictionary<String, Object> current = configuration.getProperties();
                if (current != null && Objects.equals(current.get(key), message))
                    return;
                Hashtable<String, Object> updated = new Hashtable<>();
                if (current != null) {
                    Enumeration<String> keys = current.keys();
                    while (keys.hasMoreElements()) {
                        String currentKey = keys.nextElement();
                        if (!"service.pid".equals(currentKey) && !currentKey.startsWith(":")
                                && !currentKey.startsWith("_felix_")) {
                            updated.put(currentKey, current.get(currentKey));
                        }
                    }
                }
                updated.put(key, message);
                configuration.update(updated);
            } catch (Exception error) {
                LOGGER.warn("Cannot update Yandex bridge diagnostic status: {}", error.toString());
            }
        }
    }

    private static String value(Map<String, ?> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : value.toString();
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return Set.of(ItemCommandEvent.TYPE);
    }

    @Override
    public void receive(Event event) {
        if (!(event instanceof ItemCommandEvent commandEvent) || EVENT_SOURCE.equals(commandEvent.getSource()))
            return;
        RemoteMirrorPlanner.Spec spec = active.get(commandEvent.getItemName());
        RemoteMirrorHttp http = remoteHttp;
        ScheduledExecutorService worker = executor;
        if (spec == null || "Group".equals(spec.type()) || http == null || worker == null || worker.isShutdown())
            return;
        try {
            worker.execute(() -> {
                try {
                    http.postCommand(spec.sourceName(), commandEvent.getItemCommand().toString());
                } catch (Exception error) {
                    LOGGER.warn("Failed to forward Yandex command for {}: {}", spec.sourceName(), error.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Configuration was changed while the command was in flight.
        }
    }

    private void pollSafely() {
        try {
            poll();
            if (!lastError.isEmpty())
                LOGGER.info("Yandex remote Item bridge recovered");
            lastError = "";
            publishStatus("bridgeDiagnostic", RemoteMirrorStatus.connected(active.size()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            String message = error.toString();
            if (!message.equals(lastError))
                LOGGER.warn("Yandex remote Item bridge poll failed: {}", message);
            lastError = message;
            publishStatus("bridgeDiagnostic", RemoteMirrorStatus.failure(error));
        }
    }

    private void poll() throws Exception {
        RemoteMirrorHttp http = remoteHttp;
        if (http == null)
            return;
        long snapshotStarted = System.nanoTime();
        JSONArray response = http.getItems();
        if (response.isEmpty())
            throw new IllegalStateException("Remote Items response is empty");
        List<RemoteMirrorPlanner.Spec> specs = RemoteMirrorPlanner.plan(response, tag, prefix);
        Map<String, RemoteMirrorPlanner.Spec> next = new HashMap<>();
        for (RemoteMirrorPlanner.Spec spec : specs) {
            try {
                if (ensureItem(spec))
                    next.put(spec.localName(), spec);
            } catch (RuntimeException error) {
                LOGGER.warn("Cannot mirror remote Item {}: {}", spec.sourceName(), error.toString());
            }
        }
        for (RemoteMirrorPlanner.Spec spec : specs) {
            if (next.containsKey(spec.localName()))
                updateState(spec, snapshotStarted);
        }
        // A partial snapshot must not delete any previously working proxy.
        if (next.size() == specs.size()) {
            for (Item item : new ArrayList<>(managedItems.getAll())) {
                String name = item.getName();
                if (name.startsWith(prefix) && !next.containsKey(name)) {
                    managedItems.remove(name);
                    LOGGER.info("Yandex remote Item removed: {}", name);
                }
            }
        }
        active = Map.copyOf(next);
        if (next.size() != specs.size()) {
            throw new IllegalStateException("Some remote Items could not be mirrored; existing Items were retained");
        }
    }

    private boolean ensureItem(RemoteMirrorPlanner.Spec spec) throws ItemNotFoundException {
        Item current = managedItems.get(spec.localName());
        if (current == null) {
            try {
                itemRegistry.getItem(spec.localName());
                LOGGER.warn("Yandex remote Item name is already in use: {}", spec.localName());
                return false;
            } catch (ItemNotFoundException absent) {
                managedItems.add(buildItem(spec));
                LOGGER.info("Yandex remote Item added: {} ({})", spec.localName(), spec.label());
                return true;
            }
        }
        if (!sameDefinition(current, spec)) {
            Item replacement = buildItem(spec);
            if (!current.getType().equals(replacement.getType())) {
                managedItems.remove(spec.localName());
                managedItems.add(replacement);
            } else {
                managedItems.update(replacement);
            }
            LOGGER.info("Yandex remote Item updated: {} ({})", spec.localName(), spec.label());
        }
        return true;
    }

    private Item buildItem(RemoteMirrorPlanner.Spec spec) {
        ItemBuilder builder = itemBuilders.newItemBuilder(spec.type(), spec.localName());
        builder.withLabel(spec.label()).withTags(spec.tags()).withGroups(spec.groups());
        if (!spec.category().isBlank())
            builder.withCategory(spec.category());
        if ("Group".equals(spec.type()) && !spec.groupType().isBlank()) {
            Item base = itemBuilders.newItemBuilder(spec.groupType(), spec.localName() + "_base").build();
            builder.withBaseItem(base);
            if (!spec.functionName().isBlank()) {
                builder.withGroupFunction(
                        RemoteGroupFunctions.create(spec.functionName(), spec.functionParams(), base));
            }
        }
        return builder.build();
    }

    private static boolean sameDefinition(Item item, RemoteMirrorPlanner.Spec spec) {
        if (item instanceof GroupItem group) {
            Item base = group.getBaseItem();
            String baseType = base == null ? "" : base.getType();
            if (!baseType.equals(spec.groupType()))
                return false;
            if (spec.functionName().isBlank() && group.getFunction() != null
                    && !(group.getFunction() instanceof GroupFunction.Equality))
                return false;
            if (!spec.functionName().isBlank()) {
                if (base == null || group.getFunction() == null)
                    return false;
                var expected = RemoteGroupFunctions.create(spec.functionName(), spec.functionParams(), base);
                if (!expected.getClass().equals(group.getFunction().getClass())
                        || !Arrays.equals(expected.getParameters(), group.getFunction().getParameters()))
                    return false;
            }
        }
        return item.getType().equals(spec.type()) && Objects.equals(item.getLabel(), spec.label())
                && Objects.equals(item.getCategory() == null ? "" : item.getCategory(), spec.category())
                && item.getTags().equals(spec.tags())
                && new HashSet<>(item.getGroupNames()).equals(new HashSet<>(spec.groups()));
    }

    private void updateState(RemoteMirrorPlanner.Spec spec, long snapshotStarted) {
        synchronized (stateLock) {
            if (eventTimes.getOrDefault(spec.localName(), 0L) > snapshotStarted)
                return;
            postState(spec.localName(), spec.state());
        }
    }

    private void postState(String localName, String value) {
        if (value.isBlank() || "NULL".equals(value) || "UNDEF".equals(value))
            return;
        try {
            Item item = itemRegistry.getItem(localName);
            State state = TypeParser.parseState(item.getAcceptedDataTypes(), value);
            if (state != null && !state.equals(item.getState())) {
                eventPublisher.post(ItemEventFactory.createStateEvent(localName, state, EVENT_SOURCE));
            }
        } catch (ItemNotFoundException error) {
            LOGGER.debug("Mirrored Item not in registry yet: {}", localName);
        } catch (RuntimeException error) {
            LOGGER.debug("Cannot parse state for {}: {}", localName, error.toString());
        }
    }

    private void eventLoop(RemoteMirrorHttp http) {
        String previousError = "";
        while (http == remoteHttp && !Thread.currentThread().isInterrupted()) {
            try (InputStream stream = http.openEvents()) {
                LOGGER.info("Yandex remote Item event stream connected");
                publishStatus("eventsDiagnostic", "✓ Поток событий подключён: изменения передаются сразу.");
                previousError = "";
                RemoteMirrorSse.read(new InputStreamReader(stream, StandardCharsets.UTF_8), this::receiveRemoteChange);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                if (http != remoteHttp || Thread.currentThread().isInterrupted())
                    return;
                String message = error.toString();
                if (!message.equals(previousError))
                    LOGGER.warn("Yandex remote Item event stream failed: {}", message);
                previousError = message;
                publishStatus("eventsDiagnostic", RemoteMirrorStatus.failure(error));
            }
            if (http != remoteHttp || Thread.currentThread().isInterrupted())
                return;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void receiveRemoteChange(RemoteMirrorSse.Change change) {
        RemoteMirrorPlanner.Spec spec = active.get(prefix + change.sourceName());
        if (spec == null || !spec.sourceName().equals(change.sourceName()))
            return;
        String localName = spec.localName();
        if (change.command()) {
            if ("Group".equals(spec.type()))
                return;
            try {
                Item item = itemRegistry.getItem(localName);
                Command command = TypeParser.parseCommand(item.getAcceptedCommandTypes(), change.value());
                if (command != null) {
                    eventPublisher.post(ItemEventFactory.createCommandEvent(localName, command, EVENT_SOURCE));
                }
            } catch (ItemNotFoundException error) {
                LOGGER.debug("Mirrored Item not in registry yet: {}", localName);
            } catch (RuntimeException error) {
                LOGGER.debug("Cannot parse command for {}: {}", localName, error.toString());
            }
        } else {
            synchronized (stateLock) {
                eventTimes.put(localName, System.nanoTime());
                postState(localName, change.value());
            }
        }
    }
}
