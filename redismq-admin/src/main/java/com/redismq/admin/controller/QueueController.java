package com.redismq.admin.controller;

import com.redismq.Message;
import com.redismq.admin.pojo.MQMessageDTO;
import com.redismq.connection.RedisMQClientUtil;
import com.redismq.queue.Queue;
import com.redismq.utils.RedisMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.redismq.constant.GlobalConstant.SPLITE;

@RestController
@RequestMapping("/topic")
public class QueueController {
    
    @Autowired
    private RedisMQTemplate redisMQTemplate;
    
    @Autowired
    private RedisMQClientUtil redisMQClientUtil;
    
    @GetMapping("page")
    public ResponseEntity<Set<Queue>> page(){
        //队列名就是topic名
        Set<Queue> allQueue = redisMQClientUtil.getQueueList();
        return ResponseEntity.ok(allQueue);
    }
    
    //根据topic名称查询虚拟队列
    @GetMapping("queueList")
    public ResponseEntity<List<String>> queueList(String topicName) {
        Set<Queue> allQueue = redisMQClientUtil.getQueueList();
        Optional<Queue> first = allQueue.stream().filter(a -> a.getQueueName().equals(topicName)).findFirst();
        Queue queue = first.get();
        Integer virtual = queue.getVirtual();
        List<String> virtualQueues = new ArrayList<>();
        for (int i = 0; i < virtual; i++) {
            String virtualQueue = queue.getQueueName() + SPLITE + i;
            virtualQueues.add(virtualQueue);
        }
        
        return ResponseEntity.ok(virtualQueues);
    }
    
    @PostMapping("sendMessage")
    public ResponseEntity<String> sendMessage(@RequestBody MQMessageDTO message) {
        Message build = Message.builder().body(message.getBody()).tag(message.getTag()).build();
        redisMQTemplate.sendMessage(build);
        return ResponseEntity.ok(null);
    }
    
    @PostMapping("sendTimingMessage")
    public ResponseEntity<String> sendTimingMessage(@RequestBody MQMessageDTO message) {
        Message build = Message.builder().body(message.getBody()).tag(message.getTag()).build();
        redisMQTemplate.sendTimingMessage(build, message.getConsumeTime());
        return ResponseEntity.ok(null);
    }
    
}