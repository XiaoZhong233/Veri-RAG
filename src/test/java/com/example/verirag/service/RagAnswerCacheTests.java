package com.example.verirag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagAnswerCacheTests {

    @Test
    void separatesChineseAndEnglishQuestionsIntoDifferentCacheBuckets() {
        assertThat(RagAnswerCache.languageBucket("How many annual leave days are available?")).isEqualTo("en");
        assertThat(RagAnswerCache.languageBucket("员工可以休多少天年假？")).isEqualTo("zh");
        assertThat(RagAnswerCache.languageBucket("REST API 文件上传使用什么格式？")).isEqualTo("zh");
    }
}
