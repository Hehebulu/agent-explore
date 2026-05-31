package com.example.customeragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.customeragent.model.SensitiveWord;
import com.example.customeragent.repository.SensitiveWordRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 安全检查服务 —— 敏感词检测
 *
 * 职责：检测用户消息是否包含平台违禁词（政治、暴恐、色情、广告等）。
 * 命中后直接阻断流程，返回"内容不符合规范"提示，不再进入后续 AI 处理。
 *
 * 缓存策略：首次调用时从 DB 加载敏感词到内存（volatile + double-check locking），
 * 后续请求直接命中内存缓存，避免每次查库。
 * 管理后台新增敏感词后调用 refreshCache() 使缓存失效。
 */
@Service
public class SecurityCheckService {

    private final SensitiveWordRepository sensitiveWordRepository;
    private volatile Set<String> cachedWords;
    private volatile List<Pattern> cachedPatterns;

    public SecurityCheckService(SensitiveWordRepository sensitiveWordRepository) {
        this.sensitiveWordRepository = sensitiveWordRepository;
    }

    /**
     * 检查文本是否包含敏感词
     *
     * 使用正则模式匹配（忽略大小写），比 contains 更精确。
     * 返回 true 表示命中，流程应终止。
     */
    public boolean containsSensitiveWord(String text) {
        ensureCacheLoaded();
        String lowerText = text.toLowerCase();
        for (Pattern pattern : cachedPatterns) {
            if (pattern.matcher(lowerText).find()) {
                return true;
            }
        }
        return false;
    }

    /** 返回命中的敏感词文本（用于日志/审计），未命中返回 null */
    public String findHitWord(String text) {
        ensureCacheLoaded();
        String lowerText = text.toLowerCase();
        for (String word : cachedWords) {
            if (lowerText.contains(word.toLowerCase())) {
                return word;
            }
        }
        return null;
    }

    /**
     * 延迟加载 + 双重检查锁
     *
     * volatile 保证多线程可见性，synchronized 保证只加载一次。
     * 同时构建 Set<String>（用于查找命中词）和 List<Pattern>（用于正则检测）。
     */
    private void ensureCacheLoaded() {
        if (cachedWords == null) {
            synchronized (this) {
                if (cachedWords == null) {
                    List<String> words = sensitiveWordRepository
                            .selectList(new LambdaQueryWrapper<SensitiveWord>().eq(SensitiveWord::isEnabled, true))
                            .stream()
                            .map(SensitiveWord::getWord)
                            .collect(Collectors.toList());
                    cachedWords = Set.copyOf(words);
                    cachedPatterns = words.stream()
                            .map(w -> Pattern.compile(Pattern.quote(w), Pattern.CASE_INSENSITIVE))
                            .collect(Collectors.toList());
                }
            }
        }
    }

    /** 刷新缓存（管理后台增删敏感词后调用） */
    public void refreshCache() {
        cachedWords = null;
        cachedPatterns = null;
    }
}
