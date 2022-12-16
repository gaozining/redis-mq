package com.redismq.utils;

import com.redismq.Message;
import com.redismq.core.RedisMQProducer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * redisMQ操作模板
 *
 * @author hzh
 * @date 2022/12/13
 */
public class RedisMQTemplate {
    private final RedisMQProducer redisMQProducer;

    public RedisMQTemplate(RedisMQProducer redisMQProducer) {
        this.redisMQProducer = redisMQProducer;
    }

    public RedisMQProducer getRedisMQProducer() {
        return redisMQProducer;
    }

    /**
     * 队列消息
     */
    public boolean sendMessage(Message message) {
        return redisMQProducer.sendMessage(message);
    }

    /**
     * 发送延时消息
     */
    public boolean sendDelayMessage(Message message, Long delayTime, TimeUnit timeUnit) {
        long millis = timeUnit.toMillis(delayTime);
        return redisMQProducer.sendDelayMessage(message, millis);
    }

    /**
     * 发送延时消息
     *
     */
    public boolean sendDelayMessage(Message message, Duration duration) {
        long millis = duration.toMillis();
        return redisMQProducer.sendDelayMessage(message, millis);
    }

    /**
     * 发送定时消息
     *
     */
    public boolean sendTimingMessage(Message message, Long excuteTime) {
        return redisMQProducer.sendTimingMessage(message, excuteTime);
    }

    /**
     * 队列消息
     */
    public boolean sendMessage(Object obj, String topic) {
        return redisMQProducer.sendMessage(obj, topic);
    }

    /**
     * 带tag的队列消息
     */
    public boolean sendMessage(Object obj, String topic, String tag) {
        return redisMQProducer.sendMessage(obj, topic, tag);
    }

    /**
     * 批量一次性打包发送队列消息  消费仍然是一对一消费
     */
    public boolean sendBatchMessage(List<?> messages, String topic) {
        return redisMQProducer.sendBatchMessage(messages, topic, "");
    }

    /**
     * 批量一次性打包发送队列消息  消费仍然是一对一消费
     */
    public boolean sendBatchMessage(List<?> messages, String topic, String tag) {
        return redisMQProducer.sendBatchMessage(messages, topic, tag);
    }


    /**
     * 带tag的队列延迟消息
     */
    public boolean sendDelayMessage(Object obj, String topic, String tag, Long delayTime, TimeUnit timeUnit) {
        long millis = timeUnit.toMillis(delayTime);
        return redisMQProducer.sendDelayMessage(obj, topic, tag, millis);
    }

    /**
     * 延迟消息
     */
    public boolean sendDelayMessage(Object obj, String topic, Long delayTime, TimeUnit timeUnit) {
        long millis = timeUnit.toMillis(delayTime);
        return redisMQProducer.sendDelayMessage(obj, topic, "", millis);
    }

    /**
     * 带tag的队列延迟消息
     */
    public boolean sendDelayMessage(Object obj, String topic, String tag, Duration duration) {
        long millis = duration.toMillis();
        return redisMQProducer.sendDelayMessage(obj, topic, tag, millis);
    }

    /**
     * 延迟消息
     */
    public boolean sendDelayMessage(Object obj, String topic, Duration duration) {
        long millis = duration.toMillis();
        return redisMQProducer.sendDelayMessage(obj, topic, "", millis);
    }

    /**
     * 定时消息
     */
    public boolean sendTimingMessage(Object obj, String topic, String tag, Long executorTime) {
        return redisMQProducer.sendTimingMessage(obj, topic, tag, executorTime);
    }

    public boolean sendTimingMessage(Object obj, String topic, Long executorTime) {
        return redisMQProducer.sendTimingMessage(obj, topic, "", executorTime);
    }

    /*
     * redis的发布订阅  直接传递实际数据即可
     */
    public void publish(String topic, Object obj) {
        redisMQProducer.publish(topic, obj);
    }

    /*
     * 尝试取消删除消息-注意消息有可能已经被消费
     */
    public void tryCancel(Message message) {
        redisMQProducer.tryCancel(message);
    }
}
