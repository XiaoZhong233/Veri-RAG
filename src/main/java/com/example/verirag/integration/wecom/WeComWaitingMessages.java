package com.example.verirag.integration.wecom;

import java.util.List;
import java.util.Objects;

/** 轻量等待文案，不调用模型；同一消息重试时保持文案一致。 */
final class WeComWaitingMessages {
    private static final List<String> CHINESE = List.of(
            "收到～小伦同学正在翻资料小本本 📒 稍等我一下下～",
            "小伦同学开工啦 🐾 正在认真核对信息，稍等一小会儿～",
            "叮～问题收到 ✨ 给小伦同学一点时间，帮你仔细看看～",
            "小伦同学正在埋头查资料 📚 整理好就来回复你～",
            "让小伦同学捋一捋相关信息 🧶 稍等我一下～",
            "小伦同学带着放大镜来啦 🔎 正在仔细查看相关资料～");
    private static final List<String> ENGLISH = List.of(
            "Got it! Xiaolun is checking the details for you 🔎 Just a little moment～",
            "Xiaolun is on the case 🐾 Taking a closer look at your question～",
            "Message received ✨ Give Xiaolun a moment to check the information～",
            "Xiaolun is looking through the notes 📚 Back with you once they're ready～",
            "Let Xiaolun untangle the details for you 🧶 One moment～",
            "Notebook open, Xiaolun at work 📒 Checking the details for you～");

    private WeComWaitingMessages() {}

    static String select(String configured, String question, String messageId) {
        if (configured == null || configured.isBlank()) return "";
        String value = configured.strip();
        // 兼容生产 .env 里原先的默认文案，升级后无需手工替换即可使用新风格。
        boolean automatic = "auto".equalsIgnoreCase(value)
                || List.of("正在检索资料，请稍候…", "正在检索资料，请稍后…",
                        "正在检索资料，请稍候...", "正在检索资料，请稍后...").contains(value);
        if (!automatic) return configured;
        boolean chinese = Objects.toString(question, "").codePoints().anyMatch(c ->
                Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        List<String> choices = chinese ? CHINESE : ENGLISH;
        return choices.get(Math.floorMod(Objects.toString(messageId, "").hashCode(), choices.size()));
    }
}
