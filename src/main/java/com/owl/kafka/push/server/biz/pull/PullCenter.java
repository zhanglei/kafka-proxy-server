package com.owl.kafka.push.server.biz.pull;

import com.owl.kafka.client.service.IdService;
import com.owl.kafka.client.transport.message.Message;
import com.owl.kafka.client.transport.protocol.Command;
import com.owl.kafka.client.transport.protocol.Header;
import com.owl.kafka.client.transport.protocol.Packet;
import com.owl.kafka.client.util.Packets;
import com.owl.kafka.push.server.biz.bo.ServerConfigs;
import com.owl.kafka.push.server.biz.service.PullRequestHoldService;
import com.owl.kafka.push.server.biz.bo.PullRequest;
import com.owl.kafka.serializer.SerializerImpl;
import com.owl.kafka.util.CollectionUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @Author: Tboy
 */
public class PullCenter{

    private static final Logger LOGGER = LoggerFactory.getLogger(PullCenter.class);

    public static PullCenter I  = new PullCenter();

    private final int queueSize = ServerConfigs.I.getServerQueueSize();

    private final LinkedBlockingQueue<Packet> retryQueue = new LinkedBlockingQueue<>(queueSize);

    private final LinkedBlockingQueue<ConsumerRecord<byte[], byte[]>> pullQueue = new LinkedBlockingQueue<>(queueSize);

    private final int singleMessageSize = 1024 * 1024 * 8;

    private final PullRequestHoldService pullRequestHoldService = new PullRequestHoldService();

    public void putMessage(ConsumerRecord<byte[], byte[]> record) throws InterruptedException{
        this.pullQueue.put(record);
        this.pullRequestHoldService.notifyMessageArriving();
    }

    public void reputMessage(Packet packet) throws InterruptedException{
        this.retryQueue.put(packet);
    }

    public Packet pull(PullRequest request, boolean isSuspend) {
        int messageCount = 1;
        long messageSize = singleMessageSize * messageCount;
        final Packet result = request.getPacket();
        while(messageCount > 0 && result.getBody().length < messageSize){
            messageCount--;
            this.poll(result);
        }
        if(result.getBody().length == 0 && isSuspend){
            pullRequestHoldService.suspend(request);
            return null;
        } else{
            return result;
        }
    }

    private Packet poll(Packet packet) {
        Packet one = retryQueue.peek();
        if(one != null){
            retryQueue.poll();
            ByteBuffer buffer = ByteBuffer.allocate(one.getBody().length + packet.getBody().length);
            buffer.put(packet.getBody());
            buffer.put(one.getBody());
            packet.setBody(buffer.array());
        } else{
            ConsumerRecord<byte[], byte[]> record = pullQueue.poll();
            if(record != null){
                Header header = new Header(record.topic(), record.partition(), record.offset(), IdService.I.getId());
                byte[] headerInBytes = SerializerImpl.getFastJsonSerializer().serialize(header);
                //
                int capacity = 4 + headerInBytes.length + 4 + record.key().length + 4 + record.value().length;
                ByteBuffer buffer = ByteBuffer.allocate(capacity + packet.getBody().length);

                buffer.put(packet.getBody());
                //
                buffer.putInt(headerInBytes.length);
                buffer.put(headerInBytes);
                //
                buffer.putInt(record.key().length);
                buffer.put(record.key());
                //
                buffer.putInt(record.value().length);
                buffer.put(record.value());

                packet.setBody(buffer.array());
            }
        }
        return packet;
    }

    public void close(){
        this.pullRequestHoldService.close();
    }
}
