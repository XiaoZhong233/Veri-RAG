package com.example.verirag.tool;

import com.example.verirag.entity.ChatMessage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 将确定性的公寓、房型、库存和报价问题路由到数据库 Tool，避免先执行向量检索。
 */
public final class PropertyQueryRouter {

    private static final Pattern STRUCTURED_TERMS = Pattern.compile(
            "公寓|房源|房型|房情|库存|租期|起租|周租|可订|预订|售罄|"
                    + "studio|ensuite|non-ensuite|room\\s*offer|weekly\\s*price",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CITY_QUERY = Pattern.compile(
            "伦敦|曼彻斯特|伯明翰|利物浦|卡迪夫|爱丁堡|格拉斯哥|牛津|"
                    + "london|manchester|birmingham|liverpool|cardiff|edinburgh|glasgow|oxford",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_ACTION = Pattern.compile(
            "多少|几个|哪些|列表|推荐|查|找|价格|报价|地址|车站|地图|入住|退房|附近");
    private static final Pattern QUOTE_TERMS = Pattern.compile(
            "room\\s*offer\\s*id|roomOfferId|房源报价id|房型报价id",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_TERMS = Pattern.compile(
            "多少(?:个)?公寓|几(?:个)?公寓|公寓总数|总共有多少|一共有多少|"
                    + "库存统计|房型数量|多少(?:个)?房型|可预订.*数量");
    private static final Pattern DETAIL_TERMS = Pattern.compile(
            "设施|配套|附近学校|附近大学|附近地标|周边|地标|景点|交通线路|公寓详情|公寓介绍|"
                    + "facilit(?:y|ies)|amenities|nearby\\s+(?:school|university)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFER_ACTION_TERMS = Pattern.compile(
            "推荐|帮我找|查找|可订|预订|入住|退房|起租|租期|价格|报价|预算|"
                    + "房源|房型|studio|ensuite|weekly\\s*price",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_TERMS = Pattern.compile(
            "有哪些公寓|公寓名单|公寓列表|公寓地址|地址在哪|"
                    + "哪个城市|哪些城市|最近车站|地图");
    private static final Pattern RECOMMEND_TERMS = Pattern.compile(
            "推荐|帮我找|查找|附近|可订|预订|入住|退房|起租|租期|"
                    + "售罄|房源|房型|studio|ensuite|weekly\\s*price",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMBIGUOUS_PROPERTY_TERMS = Pattern.compile(
            "住宿|宿舍|租房|住哪|住在|学校|大学|校区|通勤|预算|英镑|镑|半年|"
                    + "ucl|kcl|lse|qmul|accommodation|apartment|residence|rent|"
                    + "campus|university|commute|怎么样|介绍一下|什么情况",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_UP_CUE = Pattern.compile(
            "^(?:那|那么|换成|改成|如果|还有|再看|这个|这家|它|同样)|呢[？?]?$");

    private PropertyQueryRouter() {
    }

    public static boolean isStructuredPropertyQuery(String question,
                                                     List<ChatMessage> history) {
        return route(question, history).structured();
    }

    public static PropertyQueryIntent route(String question,
                                            List<ChatMessage> history) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return PropertyQueryIntent.NONE;
        }
        PropertyQueryIntent direct = routeCurrent(normalized);
        if (direct.structured()) {
            return direct;
        }
        // “那曼彻斯特呢”等短追问继承上一轮具体 Tool 意图。
        if (normalized.length() <= 30
                && FOLLOW_UP_CUE.matcher(normalized).find()
                && history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage message = history.get(i);
                if (!"USER".equals(message.getRole())) {
                    continue;
                }
                PropertyQueryIntent inherited =
                        routeCurrent(normalize(message.getContent()));
                if (inherited.structured()) {
                    return inherited;
                }
            }
        }
        return PropertyQueryIntent.NONE;
    }

    /**
     * 只有存在房源领域或模糊询问信号、但确定性规则未命中时，才值得增加一次模型分类。
     * 明显的普通知识问题直接走 RAG，避免每个请求都多调用一次模型。
     */
    public static boolean needsModelClassification(String question,
                                                   List<ChatMessage> history) {
        if (route(question, history).structured()) {
            return false;
        }
        String normalized = normalize(question);
        return !normalized.isBlank()
                && AMBIGUOUS_PROPERTY_TERMS.matcher(normalized).find();
    }

    private static PropertyQueryIntent routeCurrent(String normalized) {
        if (QUOTE_TERMS.matcher(normalized).find()) {
            return PropertyQueryIntent.QUOTE;
        }
        if (SUMMARY_TERMS.matcher(normalized).find()) {
            return PropertyQueryIntent.SUMMARY;
        }
        if (DETAIL_TERMS.matcher(normalized).find()
                && !OFFER_ACTION_TERMS.matcher(normalized).find()) {
            return PropertyQueryIntent.DETAIL;
        }
        boolean recommendation = RECOMMEND_TERMS.matcher(normalized).find();
        if (recommendation) {
            return PropertyQueryIntent.RECOMMEND;
        }
        if (LIST_TERMS.matcher(normalized).find()) {
            return PropertyQueryIntent.LIST;
        }
        if (STRUCTURED_TERMS.matcher(normalized).find()) {
            return PropertyQueryIntent.RECOMMEND;
        }
        if (CITY_QUERY.matcher(normalized).find()
                && QUERY_ACTION.matcher(normalized).find()) {
            return PropertyQueryIntent.LIST;
        }
        return PropertyQueryIntent.NONE;
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").strip().toLowerCase(Locale.ROOT);
    }
}
