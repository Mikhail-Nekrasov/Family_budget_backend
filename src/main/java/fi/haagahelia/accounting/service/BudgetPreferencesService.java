package fi.haagahelia.accounting.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.UpdateAccountOrderRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class BudgetPreferencesService {
    public record Preferences(String revision, JsonNode settings) {}
    private record Stored(long version, ObjectNode settings) {}
    private static final String ACCOUNT_ORDER = "order:account";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final SyncCommandRepository commands;
    private final UserAccountOrderService accountOrder;

    public BudgetPreferencesService(JdbcTemplate jdbc, JsonMapper json,
            SyncCommandRepository commands, UserAccountOrderService accountOrder) {
        this.jdbc = jdbc;
        this.json = json;
        this.commands = commands;
        this.accountOrder = accountOrder;
    }

    @Transactional
    public Preferences get(User user) {
        commands.lockUser(user.getId());
        return snapshot(user, read(user.getId()));
    }

    @Transactional
    public Preferences update(User user, Preferences request) {
        commands.lockUser(user.getId());
        var stored = read(user.getId());
        var before = snapshot(user, stored);
        if (!before.revision().equals(request.revision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget preferences changed");
        }
        if (!(request.settings() instanceof ObjectNode changes)) throw invalid();
        var owned = ownedLists(user.getId());
        var next = ((ObjectNode) before.settings()).deepCopy();
        for (var property : changes.properties()) {
            String key = property.getKey();
            JsonNode value = property.getValue();
            if (owned.containsKey(key)) {
                if (!value.isArray()) throw invalid();
                var ids = new HashSet<UUID>();
                var cleaned = json.createArrayNode();
                for (JsonNode item : value) {
                    UUID id = parseId(item.asString());
                    if (!ids.add(id)) throw invalid();
                    if (owned.get(key).contains(id)) cleaned.add(id.toString());
                }
                next.set(key, cleaned);
            } else {
                String[] parts = key.split(":", -1);
                if (parts.length != 3 || !validAppearance(parts[0], parts[2], value)) throw invalid();
                UUID id = parseId(parts[1]);
                if (owns(owned, parts[0], id)) next.set(key, value);
            }
        }

        var previousOrder = before.settings().get(ACCOUNT_ORDER);
        var newOrder = next.remove(ACCOUNT_ORDER);
        if (newOrder != null && !newOrder.equals(previousOrder)) {
            var ids = newOrder.valueStream().map(item -> parseId(item.asString())).toList();
            var version = accountOrder.getOrder(user).version();
            accountOrder.saveOrder(UUID.randomUUID(), new UpdateAccountOrderRequest(ids, version), user);
        }
        if (!next.equals(stored.settings())) {
            jdbc.update("""
                INSERT INTO budget_preferences (user_id, version, settings) VALUES (?, 1, CAST(? AS jsonb))
                ON CONFLICT (user_id) DO UPDATE SET version = budget_preferences.version + 1, settings = EXCLUDED.settings
                """, user.getId(), json.writeValueAsString(next));
            stored = new Stored(stored.version() + 1, next);
        }
        return snapshot(user, stored);
    }

    private Preferences snapshot(User user, Stored stored) {
        var order = accountOrder.getOrder(user);
        var owned = ownedLists(user.getId());
        var result = json.createObjectNode();
        for (var property : stored.settings().properties()) {
            String key = property.getKey();
            if (owned.containsKey(key)) {
                var filtered = result.putArray(key);
                property.getValue().forEach(item -> {
                    if (owned.get(key).contains(parseId(item.asString()))) filtered.add(item.asString());
                });
            } else {
                String[] parts = key.split(":", -1);
                if (owns(owned, parts[0], parseId(parts[1]))) result.set(key, property.getValue());
            }
        }
        if (order.version() > 0) {
            var accountIds = result.putArray(ACCOUNT_ORDER);
            order.accountSyncIds().stream().filter(owned.get(ACCOUNT_ORDER)::contains)
                .forEach(id -> accountIds.add(id.toString()));
        }
        return new Preferences(stored.version() + ":" + order.version(), result);
    }

    private Stored read(long userId) {
        return jdbc.query("SELECT version, CAST(settings AS TEXT) FROM budget_preferences WHERE user_id = ?",
            (rs, row) -> new Stored(rs.getLong(1), (ObjectNode) json.readTree(rs.getString(2))), userId)
            .stream().findFirst().orElseGet(() -> new Stored(0, json.createObjectNode()));
    }

    private Map<String, Set<UUID>> ownedLists(long userId) {
        var result = new HashMap<String, Set<UUID>>();
        result.put(ACCOUNT_ORDER, new HashSet<>(jdbc.queryForList(
            "SELECT sync_id FROM account WHERE user_id = ?", UUID.class, userId)));
        for (String type : new String[] {"INCOME", "EXPENSE"}) {
            result.put("order:" + type, new HashSet<>(jdbc.queryForList(
                "SELECT sync_id FROM category WHERE user_id = ? AND type = ?", UUID.class, userId, type)));
        }
        return result;
    }

    private boolean owns(Map<String, Set<UUID>> owned, String entity, UUID id) {
        return "account".equals(entity) ? owned.get(ACCOUNT_ORDER).contains(id)
            : "category".equals(entity) && (owned.get("order:INCOME").contains(id) || owned.get("order:EXPENSE").contains(id));
    }

    private boolean validAppearance(String entity, String field, JsonNode value) {
        if (!"account".equals(entity) && !"category".equals(entity)) return false;
        return switch (field) {
            case "color" -> value.isNull() || (value.isTextual() && value.asString().matches("#[0-9A-F]{6}"));
            case "icon" -> "category".equals(entity) && value.isTextual()
                && value.asString().matches("[A-Za-z0-9_-]{1,64}");
            case "hidden" -> "account".equals(entity) && value.isBoolean();
            default -> false;
        };
    }

    private UUID parseId(String value) {
        try {
            if (value == null || value.length() != 36) throw invalid();
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget preferences");
    }
}
