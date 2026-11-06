package com.example.verirag.integration.wecom;

import com.example.verirag.tool.PropertyQueryTools;
import com.example.verirag.tool.PropertyQueryTools.*;
import com.example.verirag.tool.ToolCallEventContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static com.example.verirag.tool.PropertyRecommendationText.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 保存每轮菜单的硬条件。按客服账号和客户隔离，重启后仍可点击，48小时自动过期。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComPropertyMenu {
    private static final String PREFIX = "property:";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final PropertyQueryTools tools;
    private final com.example.verirag.mapper.ChatMessageMapper messages;
    private final com.example.verirag.mapper.ChatSessionMapper sessions;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public record Recommendation(String overview, String heading, List<WeComKfApiClient.MenuItem> items) {}
    public record Detail(Long sessionId, String question, String answer) {}

    public boolean isMenu(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** 保留查询条件用于省略式追问，但新咨询开始后旧菜单不再可点击。 */
    public java.util.Map<String, Object> lastContext(String kf, String user, Long session) throws Exception {
        String json = redis.opsForValue().get(contextKey(kf, user));
        if (json == null) return java.util.Map.of();
        JsonNode snapshot = mapper.readTree(json);
        if (!Objects.equals(session, snapshot.path("sessionId").asLong())) return java.util.Map.of();
        return mapper.convertValue(snapshot.path("filters"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public void invalidate(String kf, String user) {
        redis.delete(activeKey(kf, user));
    }

    public Recommendation create(String kf, String user, String messageId, Long sessionId,
                                 String question, ToolCallEventContext.Event event) throws Exception {
        if (sessionId == null || event == null || !(event.result() instanceof RoomOfferSearchResult result)
                || event.parameters().isEmpty()) {
            return null;
        }
        boolean zh = question.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        String token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(messageId.getBytes(StandardCharsets.UTF_8)), 0, 12);
        var snapshot = mapper.createObjectNode();
        snapshot.put("sessionId", sessionId);
        snapshot.put("chinese", zh);
        snapshot.set("filters", mapper.valueToTree(event.parameters()));
        // 即使新条件没有结果，也保留最新条件，并使上一轮的菜单失效。
        redis.opsForValue().set(contextKey(kf, user), mapper.writeValueAsString(snapshot), Duration.ofHours(48));
        invalidate(kf, user);
        if (result.residences().isEmpty()) return null;
        var residences = snapshot.putArray("residences");
        var groups = result.residences().stream().limit(4).toList();
        var items = new java.util.ArrayList<WeComKfApiClient.MenuItem>();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            residences.addObject().put("id", group.residenceId()).put("name", group.residenceName());
            String label = (zh ? "查看 " : "View ") + group.residenceName();
            items.add(new WeComKfApiClient.MenuItem(PREFIX + token + ":" + i,
                    WeComKfMessageService.truncateUtf8(label, 128)));
        }
        redis.opsForValue().set(key(kf, user, token), mapper.writeValueAsString(snapshot), Duration.ofHours(48));
        redis.opsForValue().set(activeKey(kf, user), token, Duration.ofHours(48));
        return new Recommendation(overview(result, zh, question, event.parameters()), zh ? "点击公寓查看房型、日期及库存：" : "Choose a property for details:",
                List.copyOf(items));
    }

    public Detail resolve(String kf, String user, Long currentSession, String menuId) throws Exception {
        if (menuId == null || !menuId.matches("property:[a-f0-9]{24}:[0-3]")) return null;
        String[] parts = menuId.split(":");
        if (!parts[1].equals(redis.opsForValue().get(activeKey(kf, user)))) return null;
        String json = redis.opsForValue().get(key(kf, user, parts[1]));
        if (json == null) return null;
        JsonNode snapshot = mapper.readTree(json);
        long session = snapshot.path("sessionId").asLong();
        if (!Objects.equals(currentSession, session)) return null;
        JsonNode selected = snapshot.path("residences").path(Integer.parseInt(parts[2]));
        if (selected.isMissingNode()) return null;
        boolean zh = snapshot.path("chinese").asBoolean();
        JsonNode f = snapshot.path("filters");
        // 原日期、租期、预算、交通偏好等全部保留，仅把公寓限定为用户点击的那一个。
        RoomOfferSearchResult refreshed = tools.searchRoomOffers(
                text(f, "city"), text(f, "residenceKeyword"), selected.path("name").asText(),
                text(f, "nearbyPlaceKeyword"), integer(f, "maxTravelMinutes"), text(f, "preferredTravelModes"),
                text(f, "startDateFrom"), text(f, "startDateTo"), integer(f, "stayWeeks"),
                text(f, "rootTypes"), f.path("maxWeeklyPrice").isNumber() ? f.path("maxWeeklyPrice").decimalValue() : null,
                f.path("includeSoldOut").asBoolean(false), 4);
        ResidenceOfferGroup group = refreshed.residences().stream()
                .filter(g -> Objects.equals(g.residenceId(), selected.path("id").asLong()))
                .findFirst().orElse(null);
        String name = selected.path("name").asText();
        String answer = group == null
                ? name + (zh ? "\n这个公寓目前没有符合你需求的房型。要不要调整一下入住时间、租期或预算？" :
                "\nThere are currently no rooms that fit your needs here. Would you like to adjust your move-in date, stay length or budget?")
                : details(group, refreshed, zh, facilities(group));
        Detail detail = new Detail(session, (zh ? "查看 " : "View ") + name, answer + "\n\n" + notice(zh));
        // 让后续“这个还有其它房型吗”等自然语言追问能看到用户刚点击的对象。
        transactions.executeWithoutResult(status -> {
            if (sessions.selectById(session) == null) throw new IllegalStateException("Chat session no longer exists");
            for (String role : List.of("USER", "ASSISTANT")) {
                var message = new com.example.verirag.entity.ChatMessage();
                message.setSessionId(session);
                message.setRole(role);
                message.setContent("USER".equals(role) ? detail.question() : detail.answer());
                message.setRefs("ASSISTANT".equals(role) ? "[]" : null);
                messages.insert(message);
            }
            sessions.touchUpdateTime(session);
        });
        return detail;
    }

    private List<String> facilities(ResidenceOfferGroup group) {
        try {
            var result = tools.getResidenceDetails(group.residenceName(), 1);
            if (result == null) return List.of();
            return result.residences().stream()
                    .filter(r -> Objects.equals(r.residenceId(), group.residenceId()))
                    .map(ResidenceDetailItem::facilities).filter(Objects::nonNull)
                    .findFirst().orElse(List.of());
        } catch (RuntimeException ex) {
            // 配套资料仅用于可选推荐理由，缺失或查询失败不能阻断主要房型详情。
            return List.of();
        }
    }


    private static String key(String kf, String user, String token) {
        return "wecom:kf:property-menu:" + kf + ":" + user + ":" + token;
    }
    private static String activeKey(String kf, String user) { return "wecom:kf:property-menu:active:" + kf + ":" + user; }
    private static String contextKey(String kf, String user) { return "wecom:kf:property-menu:context:" + kf + ":" + user; }
    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private static Integer integer(JsonNode node, String field) { return node.path(field).isIntegralNumber() ? node.path(field).asInt() : null; }
}
