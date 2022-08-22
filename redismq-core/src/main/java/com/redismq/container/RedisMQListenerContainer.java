package com.redismq.container;


import com.redismq.LocalMessageManager;
import com.redismq.Message;
import com.redismq.core.RedisListenerRunnable;
import com.redismq.constant.AckMode;
import com.redismq.delay.DelayTimeoutTask;
import com.redismq.delay.DelayTimeoutTaskManager;
import com.redismq.exception.RedisMqException;
import com.redismq.factory.DefaultRedisListenerContainerFactory;
import com.redismq.queue.Queue;
import com.redismq.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.redismq.constant.RedisMQConstant.getMaualLock;

/**
 * @author hzh
 * @date 2021/8/10
 * redis延时队列实现   通过发布订阅和时间轮实现高性能。  一个queue对应一个端点对应多个queue:tag
 */
public class RedisMQListenerContainer extends AbstractMessageListenerContainer {
    protected static final Logger log = LoggerFactory.getLogger(RedisMQListenerContainer.class);
    private final ScheduledThreadPoolExecutor lifeExtensionThread = new ScheduledThreadPoolExecutor(1);
    private final DelayTimeoutTaskManager delayTimeoutTaskManager = new DelayTimeoutTaskManager();
    private volatile ScheduledFuture<?> scheduledFuture;
    private final ThreadPoolExecutor boss = new ThreadPoolExecutor(1, 1,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactory() {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private static final String NAME_PREFIX = "REDIS-MQ-BOSS-";

        {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, NAME_PREFIX + threadNumber.getAndIncrement());
            t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    });


    private final ThreadPoolExecutor work = new ThreadPoolExecutor(getConcurrency(), getMaxConcurrency(),
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactory() {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private static final String NAME_PREFIX = "REDIS-MQ-WORK-";

        {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, NAME_PREFIX + threadNumber.getAndIncrement());
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    });


    @Override
    public void doStop() {
        work.shutdown();
        try {
            if (!work.awaitTermination(3L, TimeUnit.SECONDS)) {
                log.warn("redismq workThreadPool shutdown timeout");
                work.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("redismq workThreadPool shutdown error", e);
            Thread.currentThread().interrupt();
        }
        delayTimeoutTaskManager.stop();
        //只要是线程休眠的状态就会停止
        boss.shutdownNow();
    }

    public RedisMQListenerContainer(DefaultRedisListenerContainerFactory redisListenerContainerFactory, Queue registerQueue) {
        super(redisListenerContainerFactory, registerQueue);
        lifeExtension();
    }

    public Set<Long> pop(String queueName) {
        Set<Long> startTimeSet = new HashSet<>();
        while (isRunning()) {
            try {
                //获取已经到时间要执行的任务  本地消息的数量相当于本地偏移量   localMessages.size()是指从这个位置之后开始啦
                long pullTime = System.currentTimeMillis();
                Set<ZSetOperations.TypedTuple<Object>> tuples = redisTemplate.opsForZSet().rangeByScoreWithScores(queueName, 0, pullTime, LocalMessageManager.LOCAL_MESSAGES.size(), super.maxConcurrency);
                if (CollectionUtils.isEmpty(tuples)) {
                    //本地消息没有消费完就先不取延时任务的.
                    if (LocalMessageManager.LOCAL_MESSAGES.size() > 0) {
                        Thread.sleep(200L);
                        continue;
                    }
                    //如果没有数据获取头部数据100条的时间.加入时间轮.到点的时候再过来取真实数据
                    Set<ZSetOperations.TypedTuple<Object>> headDatas = redisTemplate.opsForZSet().rangeWithScores(queueName, 0, 100);
                    if (headDatas != null) {
                        for (ZSetOperations.TypedTuple<Object> headData : headDatas) {
                            Double score = headData.getScore();
                            if (score != null) {
                                startTimeSet.add(score.longValue());
                            }
                        }
                    }
                    break;
                }
                for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
                    Message message = (Message) tuple.getValue();
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        if (isRunning()) {
                            log.info("redismq acquire semaphore InterruptedException", e);
                        }
                        Thread.currentThread().interrupt();
                    }
                    //手动ack
                    Boolean success = null;
                    try {
                        if (AckMode.MAUAL.equals(ackMode)) {
                            success = redisTemplate.opsForValue().setIfAbsent(getMaualLock(message.getId()), "", Duration.ofSeconds(60));
                            if (success == null || !success) {
                                continue;
                            }
                            Message msg = LocalMessageManager.LOCAL_MESSAGES.putIfAbsent(message.getId(), message);
                            if (msg != null) {
                                continue;
                            }
                        } else {
                            Long remove = redisTemplate.opsForZSet().remove(queueName, message);
                            if (remove == null || remove <= 0) {
                                continue;
                            }
                        }
                        String id = super.getRunableKey(message.getTag());
                        RedisListenerRunnable runnable = super.getRedisListenerRunnable(id, message);
                        if (runnable == null) {
                            throw new RedisMqException("redismq not found tag runnable");
                        }
                        // 多线程执行完毕后semaphore.release();
                        work.execute(runnable);
                    } catch (Exception e) {
                        if (isRunning()) {
                            log.error("redismq listener container error ", e);
                            //如果异常直接释放资源，否则线程执行完毕才释放
                            if (success != null && success) {
                                redisTemplate.delete(message.getId());
                            }
                        }
                        semaphore.release();
                    }
                }
            } catch (Exception e) {
                if (isRunning()) {
                    //报错需要  semaphore.release();
                    log.error("redismq pop error", e);
                    if (e.getMessage().contains("WRONGTYPE Operation against a key holding the wrong kind of value")) {
                        log.error("redismq [ERROR] queue not is zset type。 cancel pop");
                        stop();
                    }
//                if (e instanceof ClassCastException){
//                    log.error("redismq [ERROR] ClassCastException",e);
//                    stop();
//                }
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                semaphore.release();
            }
        }
        return startTimeSet;
    }


    @Override
    public void repush() {
        throw new RedisMqException("延时队列不存在的方法  repush()");
    }


    public void start(Long startTime) {
        running();
        //为空说明当前能获取到数据
        DelayTimeoutTask task1 = delayTimeoutTaskManager.computeIfAbsent(queueName, task -> new DelayTimeoutTask() {
            @Override
            protected Set<Long> pullTask() {
                List<String> virtualQueues = QueueManager.CURRENT_VIRTUAL_QUEUES.get(queueName);
                if (CollectionUtils.isEmpty(virtualQueues)) {
                    return null;
                }
                Set<Long> nextTimeSet = new HashSet<>();
                while (isRunning()) {
                    int i = 0;
                    for (String virtualQueue : virtualQueues) {
                        Set<Long> pop = pop(virtualQueue);
                        //为空说明当前能获取到数据
                        nextTimeSet.addAll(pop);
                        i++;
                    }
                    if (i >= virtualQueues.size()) {
                        return nextTimeSet;
                    }
                }
//                lifeExtensionCancel();
                return nextTimeSet;
            }
        });
        delayTimeoutTaskManager.schedule(task1, startTime);
    }

    private void lifeExtension() {
        if (AckMode.MAUAL.equals(ackMode)) {
            if (scheduledFuture == null || scheduledFuture.isCancelled()) {
                scheduledFuture = lifeExtensionThread.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        if (!CollectionUtils.isEmpty(LocalMessageManager.LOCAL_MESSAGES)) {
                            for (Message message : LocalMessageManager.LOCAL_MESSAGES.values()) {
                                String messageId = message.getId();
                                String lua = "if (redis.call('exists', KEYS[1]) == 1) then " +
                                        "redis.call('expire', KEYS[1], 60); " +
                                        "return 1; " +
                                        "end; " +
                                        "return 0;";
                                try {
                                    DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(lua, Long.class);
                                    List<String> list = new ArrayList<>();
                                    list.add(getMaualLock(messageId));
                                    redisTemplate.execute(redisScript, list);
//                                redisTemplate.expire(messageId, Duration.ofSeconds(60));
                                } catch (Exception e) {
                                    if (isRunning()) {
                                        log.error("lifeExtension  redisTemplate.expire Exception", e);
                                    }
                                }
                            }
                        }
                    }
                }, 60, 30, TimeUnit.SECONDS);
            }
        }
    }
    // 不做取消的逻辑.在并发量大的时候频繁取消反而消耗性能.
//    private void lifeExtensionCancel() {
//        try {
//            if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
//                boolean cancel = scheduledFuture.cancel(false);
//                scheduledFuture = null;
//            }
//        } catch (Exception e) {
//            log.error("lifeExtensionCancel Exception:", e);
//        }
//    }

//原来在pop()方法下面
//                String script = "local expiredValues = redis.call('zrangebyscore', KEYS[2], 0, ARGV[1], 'limit', 0, ARGV[2]);\n" +
//                        "if #expiredValues > 0 then\n" +
//                        "       local count = redis.call('zrem', KEYS[2], unpack(expiredValues));\n" +
//                        "       if count > 0 then\n" +
//                        "       for i, v in ipairs(expiredValues) do\n" +
//                        "       redis.call('rpush', KEYS[1], v);\n" +
//                        "       end;\n" +
//                        "       end;\n" +
//                        "end;\n" +
//                        "local value = redis.call('zrange', KEYS[2], 0, 0, 'WITHSCORES');\n" +
//                        "if value[1] ~= nil then\n" +
//                        "return value[2];\n" +
//                        "end\n" +
//                        "return nil;";
////        String script="local value = redis.call('LRANGE',KEYS[1],0,9);\n" +
////                "if value  then\n" +
////                "             redis.call('sadd', KEYS[2], value)\n" +
////                "             redis.call('LTRIM',KEYS[1],10,-1);\n" +
////                "return value\n" +
////                "   else\n" +
////                "     return nil\n" +
////                "end;";
//                //低版本用List  高版本要换成获取到的实际的类 就是Message
//                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
//                List<String> list = new ArrayList<>();
//                list.add(getQueueName() + "_delay_list");
//                list.add(getQueueName());
//                return redisTemplate.execute(redisScript, list, System.currentTimeMillis(), 100);
}

