package com.example.verirag.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Blocks explicit attempts to override instructions or obtain internal secrets before retrieval
 * and model invocation. This is a deterministic first line of defence, not a replacement for
 * the model's system prompt or normal authorization checks.
 */
@Component
public class PromptInjectionGuard {

    private static final List<Rule> RULES = List.of(
            new Rule("instruction_override", Pattern.compile(
                    "(?:忽略|无视|跳过).{0,24}(?:规则|指令|提示词|instructions?|prompt)")),
            new Rule("instruction_override", Pattern.compile(
                    "(?:ignore|disregard|bypass).{0,24}(?:previous|above|all|system|developer).{0,24}(?:instruction|prompt|rule)")),
            new Rule("internal_prompt_exfiltration", Pattern.compile(
                    "(?:输出|显示|泄露|提供|告诉|给我|show|reveal|dump|give).{0,32}(?:系统提示词|system\\s*prompt|developer\\s*message|hidden\\s*prompt)")),
            new Rule("secret_exfiltration", Pattern.compile(
                    "(?:输出|显示|泄露|提供|告诉|给我|show|reveal|dump|give).{0,40}(?:数据库密码|database\\s*password|api\\s*key|jwt\\s*secret|token|环境变量|connection\\s*string|密钥|secret)")),
            new Rule("secret_exfiltration", Pattern.compile(
                    "(?:数据库密码|database\\s*password|api\\s*key|jwt\\s*secret|环境变量|connection\\s*string).{0,32}(?:输出|显示|泄露|提供|告诉|show|reveal|dump|give)"))
    );

    public Decision inspect(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return new Decision(true, rule.policy());
            }
        }
        return new Decision(false, null);
    }

    public record Decision(boolean blocked, String policy) {}

    private record Rule(String policy, Pattern pattern) {}
}
