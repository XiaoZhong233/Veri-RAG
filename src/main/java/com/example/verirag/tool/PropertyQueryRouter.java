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

    private PropertyQueryRouter() {
    }

    public static boolean isStructuredPropertyQuery(String question,
                                                     List<ChatMessage> history) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        if (STRUCTURED_TERMS.matcher(normalized).find()) {
            return true;
        }
        if (CITY_QUERY.matcher(normalized).find()
                && QUERY_ACTION.matcher(normalized).find()) {
            return true;
        }
        // “那曼彻斯特呢”“价格呢”等短追问继承上一轮房源语境。
        if (normalized.length() <= 30 && history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage message = history.get(i);
                if ("USER".equals(message.getRole())
                        && STRUCTURED_TERMS.matcher(normalize(message.getContent())).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").strip().toLowerCase(Locale.ROOT);
    }
}
