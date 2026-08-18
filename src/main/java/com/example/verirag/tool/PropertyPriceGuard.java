package com.example.verirag.tool;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 房源对话的最终价格出口。即使模型未遵守提示词，也不允许向终端用户输出具体金额。
 */
@Component
public class PropertyPriceGuard {

    static final String NOTICE_ZH = "具体价格及可订状态须由 Londonist 顾问最终确认。";
    static final String NOTICE_EN =
            "Exact pricing and availability must be confirmed by a Londonist consultant.";
    private static final String NUMBER = "\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?";
    private static final Pattern PREFIXED_PRICE = Pattern.compile(
            "(?i)(?:£|GBP\\s*)\\s*" + NUMBER
                    + "(?:\\s*(?:-|–|—|~|至|到)\\s*(?:£|GBP\\s*)?\\s*" + NUMBER + ")?"
                    + "(?:\\s*(?:/\\s*(?:周|week)|每周|per\\s+week|p/?w))?");
    private static final Pattern SUFFIXED_PRICE = Pattern.compile(
            NUMBER + "(?:\\s*(?:-|–|—|~|至|到)\\s*" + NUMBER + ")?"
                    + "\\s*(?:英镑|镑|pounds?)(?:\\s*(?:/周|每周|per\\s+week))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern CURRENCY = Pattern.compile(
            "£|\\bGBP\\b|英镑|镑|\\bpounds?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_TERMS = Pattern.compile(
            "价格|报价|预算|周租|总价|底价|采购价|\\bprice\\b|\\bquote\\b|"
                    + "\\bbudget\\b|\\bcost\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY_TERMS = Pattern.compile(
            "公寓|房源|房型|住宿|租房|入住|租期|studio|ensuite|accommodation|"
                    + "apartment|residence|room|booking|internal\s+pric(?:e|ing)|price\s+tiers?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGLISH_NOTICE = Pattern.compile(
            "(?im)^\\s*>?\\s*Exact pricing and availability must be confirmed by a Londonist consultant\\.\\s*$");
    private static final Pattern CHINESE_NOTICE = Pattern.compile(
            "(?m)^\\s*>?\\s*具体价格及可订状态须由 Londonist 顾问最终确认。\\s*$");
    private static final Pattern ENGLISH_REDACTED_BUDGET = Pattern.compile(
            "(?i)(?:with|within)\\s+(?:a\\s+)?(?:maximum\\s+weekly\\s+)?"
                    + "(?:budget(?:\\s+of)?\\s+)?price on request(?:\\s+budget)?"
                    + "|(?:maximum\\s+weekly\\s+budget\\s+of|weekly\\s+budget\\s+of|budget\\s+of)"
                    + "\\s+price on request|price on request budget");
    private static final Pattern CHINESE_REDACTED_BUDGET = Pattern.compile(
            "(?:每周)?预算(?:为|是|不超过|在)?\\s*价格请咨询顾问(?:以内|以下|左右)?");

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
        String replacement = chinese ? "价格请咨询顾问" : "price on request";
        value = PREFIXED_PRICE.matcher(value).replaceAll(replacement);
        value = SUFFIXED_PRICE.matcher(value).replaceAll(replacement);
        value = ENGLISH_REDACTED_BUDGET.matcher(value).replaceAll("within your stated budget");
        value = CHINESE_REDACTED_BUDGET.matcher(value).replaceAll("您的预算条件")
                .replace("您的预算条件的条件", "您的预算条件")
                .replace("每周价格请咨询顾问的预算", "您的预算条件")
                .replace("a within your stated budget", "within your stated budget")
                .replace("the within your stated budget", "your stated budget");
        value = (chinese ? ENGLISH_NOTICE : CHINESE_NOTICE).matcher(value).replaceAll("").stripTrailing();
        String notice = chinese ? NOTICE_ZH : NOTICE_EN;
        if (!value.contains(notice)) {
            value = value.stripTrailing() + "\n\n> " + notice;
        }
        return value;
    }
}
