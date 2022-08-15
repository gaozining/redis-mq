package com.redismq.core;


import com.redismq.constant.RedisMQConstant;
import com.redismq.constant.PushMessage;
import com.redismq.queue.Queue;
import com.redismq.queue.QueueManager;
import com.redismq.rebalance.ClientConfig;
import com.redismq.rebalance.RebalanceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.data.redis.connection.util.ByteArrayWrapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.redismq.constant.RedisMQConstant.*;

public class RedisMqClient {
    private final ScheduledThreadPoolExecutor registerThread = new ScheduledThreadPoolExecutor(1);
    private final ScheduledThreadPoolExecutor rebalanceThread = new ScheduledThreadPoolExecutor(1);
    protected static final Logger log = LoggerFactory.getLogger(RedisMqClient.class);
    private final RedisListenerContainerManager redisListenerContainerManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String clientId;
    private final RebalanceImpl rebalance;
//    private Subscription subscription;
    private RedisMessageListenerContainer redisMessageListenerContainer;
    private volatile boolean isSub;

    public RedisMqClient(RedisTemplate<String, Object> redisTemplate, RedisListenerContainerManager redisListenerContainerManager, RebalanceImpl rebalance) {
        this.redisTemplate = redisTemplate;
        this.clientId = ClientConfig.getLocalAddress();
        this.redisListenerContainerManager = redisListenerContainerManager;
        this.rebalance = rebalance;
    }

    public RedisMessageListenerContainer getRedisMessageListenerContainer() {
        return redisMessageListenerContainer;
    }

    public void setRedisMessageListenerContainer(RedisMessageListenerContainer redisMessageListenerContainer) {
        this.redisMessageListenerContainer = redisMessageListenerContainer;
    }

    public String getClientId() {
        return clientId;
    }

    public RedisListenerContainerManager getRedisListenerContainerManager() {
        return redisListenerContainerManager;
    }


    public void registerClient() {
        log.debug("registerClient :{}", clientId);
        //注册客户端
        redisTemplate.opsForZSet().add(getClientKey(), clientId, System.currentTimeMillis());
    }

    public Set<String> allClient() {
        return redisTemplate.opsForZSet().rangeByScore(getClientKey(), 1, Double.MAX_VALUE).stream().map(Object::toString).collect(Collectors.toSet());
    }

    public Long removeExpireClients() {
        // 60秒以外的客户端
        long max = System.currentTimeMillis() - 40000L;
        return redisTemplate.opsForZSet().removeRangeByScore(getClientKey(), 0, max);
    }

    public Long removeAllClient() {
        log.info("redismq removeAllClient");
        return redisTemplate.opsForZSet().removeRangeByScore(getClientKey(), 0, Double.MAX_VALUE);
    }

    public void destory() {
//        closeSubscribe();
        redisTemplate.opsForZSet().remove(getClientKey(), clientId);
        publishRebalance();
        log.info("redismq client remove");
        //停止任务
        redisListenerContainerManager.stopAll();
    }

//    private void closeSubscribe() {
//        if (subscription != null) {
//            subscription.close();
//        }
//    }

    public void start() {
        // 清理所有客户端
        removeAllClient();
        // 当前客户端暂时监听所有队列  等待下次重平衡所有队列.防止新加入客户端时.正好有客户端退出.而出现有几个队列在1分钟内没有客户端监听的情况 doReblance已经注册
//        registerClient();
        // 重平衡
        rebalance();
        //订阅push消息
//        subscribe();
        // 订阅平衡消息
        rebalanceSubscribe();
        // 30秒自动注册
        startRegisterClientTask();
        // 20秒自动重平衡
        startRebalanceTask();
        //启动队列监控
        redisListenerContainerManager.startRedisListener();
        //启动延时队列监控
        redisListenerContainerManager.startDelayRedisListener();
    }


    // 多个服务应该只有一个执行重平衡
    public void rebalanceTask() {
        String lockKey = getRebalanceLock();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, "", 30, TimeUnit.SECONDS);
        if (success != null && success) {
            Long count = removeExpireClients();
            if (count != null && count > 0) {
                log.info("doRebalance removeExpireClients count=:{}", count);
                rebalance();
            }
        }
    }

    private void rebalance() {
        // 发布重平衡 会让其他服务暂停拉取消息
        publishRebalance();
        // 在执行重平衡.当前服务暂停重新分配拉取消息 放到注册客户端中
        doRebalance();
    }

    // 暂停消息分配.重新负载均衡后.重新拉取消息
    public void doRebalance() {
        registerClient();
        redisListenerContainerManager.pauseAll();
        rebalance.rebalance(allClient(), clientId);
        repush();
    }

    private void publishRebalance() {
        redisTemplate.convertAndSend(getRebalanceTopic(), clientId);
    }

    //启动时对任务重新进行拉取
    public void repush() {
        Map<String, List<String>> queues = QueueManager.CURRENT_VIRTUAL_QUEUES;
        boolean isEmpty = queues.values().stream().allMatch(CollectionUtils::isEmpty);
        if (isEmpty) {
            unSubscribe();
            return;
        }
        subscribe();
        queues.forEach((k, v) -> {
            Queue queue = QueueManager.getQueue(k);
            if (queue == null) {
                return;
            }
            PushMessage pushMessage = new PushMessage();
            pushMessage.setQueue(k);
            pushMessage.setTimestamp(System.currentTimeMillis());
            LinkedBlockingQueue<PushMessage> delayBlockingQueue = redisListenerContainerManager.getDelayBlockingQueue();
            LinkedBlockingQueue<String> linkedBlockingQueue = redisListenerContainerManager.getLinkedBlockingQueue();
            boolean delayState = queue.getDelayState();
            if (delayState) {
                delayBlockingQueue.add(pushMessage);
            } else {
                linkedBlockingQueue.add(k);
            }
        });
    }

    //订阅
    public synchronized void subscribe() {
        if (!isSub) {
            RedisMqClient redisMqClient = this;
            redisMessageListenerContainer.addMessageListener(new RedisPushListener(redisMqClient), new ChannelTopic(RedisMQConstant.getTopic()));
            isSub = true;
        }
    }

    //取消订阅
    public synchronized void unSubscribe() {
        if (isSub) {
            RedisMqClient redisMqClient = this;
            redisMessageListenerContainer.removeMessageListener(new RedisPushListener(redisMqClient), new ChannelTopic(RedisMQConstant.getTopic()));
            isSub = false;
        }
    }

    public void rebalanceSubscribe() {
        RedisMqClient redisMqClient = this;
        redisMessageListenerContainer.addMessageListener(new RedisRebalanceListener(redisMqClient), new ChannelTopic(RedisMQConstant.getRebalanceTopic()));
    }

    protected byte[][] unwrap(Collection<ByteArrayWrapper> holders) {
        if (CollectionUtils.isEmpty(holders)) {
            return new byte[0][];
        }

        byte[][] unwrapped = new byte[holders.size()][];

        int index = 0;
        for (ByteArrayWrapper arrayHolder : holders) {
            unwrapped[index++] = arrayHolder.getArray();
        }
        return unwrapped;
    }

    public void startRegisterClientTask() {
        registerThread.scheduleAtFixedRate(this::registerClient, 30, 30, TimeUnit.SECONDS);
    }

    public void startRebalanceTask() {
        rebalanceThread.scheduleAtFixedRate(this::rebalanceTask, 10, 30, TimeUnit.SECONDS);
    }
}
