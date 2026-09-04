package com.example.verirag.tool;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 房源对话的最终响应出口。允许展示面向客户的参考价格，
 * 但仍会移除可能被理解为锁房或锁价承诺的措辞，并补充最终确认提示。
 */
@Component
public class PropertyPriceGuard {

    static final String NOTICE_ZH = "参考价格及可订状态须由 Londonist 顾问最终确认。";
    static final String NOTICE_EN =
            "Reference pricing and availability must be confirmed by a Londonist consultant.";
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern CURRENCY = Pattern.compile(
            "£|\\bGBP\\b|英镑|镑|\\bpounds?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_TERMS = Pattern.compile(
            "价格|报价|参考价|周价|多少钱|预算|周租|总价|底价|采购价|\\bprice\\b|\\bquote\\b|"
                    + "\\bbudget\\b|\\bcost\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY_TERMS = Pattern.compile(
            "公寓|房源|房型|住宿|租房|入住|租期|studio|ensuite|accommodation|"
                    + "apartment|residence|room|booking|internal\s+pric(?:e|ing)|price\s+tiers?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGLISH_NOTICE = Pattern.compile(
            "(?im)^\\s*>?\\s*(?:Exact|Reference) pricing and availability must be confirmed by a Londonist consultant\\.\\s*$");
    private static final Pattern CHINESE_NOTICE = Pattern.compile(
            "(?m)^\\s*>?\\s*(?:具体|参考)价格及可订状态须由 Londonist 顾问最终确认。\\s*$");
    private static final Pattern UNSAFE_COMMITMENT_WORDING = Pattern.compile(
            "优先(?:为你|为您)?预留房源|安排锁房|人工锁房(?:与|和|及)?预订确认|预订成功确认|"
                    + "锁定价格|保证价格|lock\\s+in\\s+(?:an\\s+)?official\\s+rates?|"
                    + "guarantee(?:d)?\\s+(?:the\\s+)?price|arrange\\s+(?:a\\s+)?room\\s+hold",
            Pattern.CASE_INSENSITIVE);

    public boolean shouldProtect(String question) {
        String value = Objects.toString(question, "");
        return CURRENCY.matcher(value).find()
                || (PRICE_TERMS.matcher(value).find()
                && PROPERTY_TERMS.matcher(value).find());
    }

    public String enforce(String answer) {
        return enforce(answer, null);
    }

    public String enforce(String answer, String question) {
        String value = Objects.toString(answer, "");
        boolean chinese = question == null ? HAN.matcher(value).find() : HAN.matcher(question).find();
        value = UNSAFE_COMMITMENT_WORDING.matcher(value)
                .replaceAll(chinese ? "核验需求并说明后续流程" :
                        "verify your request and explain the next steps");
        value = (chinese ? ENGLISH_NOTICE : CHINESE_NOTICE).matcher(value).replaceAll("").stripTrailing();
        String notice = chinese ? NOTICE_ZH : NOTICE_EN;
        if (!value.contains(notice)) {
            value = value.stripTrailing() + "\n\n> " + notice;
        }
        return value;
    }
}
