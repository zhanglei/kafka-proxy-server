package com.owl.kafka.proxy.server.biz.pull;

import com.owl.kafka.client.proxy.service.IdService;
import com.owl.kafka.client.proxy.service.PullStatus;
import com.owl.kafka.client.proxy.transport.alloc.ByteBufferPool;
import com.owl.kafka.client.proxy.transport.message.Header;
import com.owl.kafka.client.proxy.transport.protocol.Command;
import com.owl.kafka.client.proxy.transport.protocol.Packet;
import com.owl.kafka.client.serializer.SerializerImpl;
import com.owl.kafka.proxy.server.biz.bo.PullRequest;
import com.owl.kafka.proxy.server.biz.bo.ServerConfigs;
import com.owl.kafka.proxy.server.biz.service.PullRequestHoldService;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * @Author: Tboy
 */
public class PullCenter{

    private static final Logger LOGGER = LoggerFactory.getLogger(PullCenter.class);

    public static PullCenter I  = new PullCenter();

    private final int queueSize = ServerConfigs.I.getServerQueueSize();

    private final ArrayBlockingQueue<Packet> retryQueue = new ArrayBlockingQueue<>(queueSize);

    private final ArrayBlockingQueue<ConsumerRecord<byte[], byte[]>> pullQueue = new ArrayBlockingQueue<>(queueSize);

    private final int pullMessageCount = ServerConfigs.I.getServerPullMessageCount();

    private final long messageSize = ServerConfigs.I.getServerPullMessageSize();

    private final PullRequestHoldService pullRequestHoldService = new PullRequestHoldService();

    private final ByteBufferPool bufferPool = ByteBufferPool.DEFAULT;

    public void putMessage(ConsumerRecord<byte[], byte[]> record) throws InterruptedException{
        this.pullQueue.put(record);
        this.pullRequestHoldService.notifyMessageArriving();
    }

    public void reputMessage(Packet packet) throws InterruptedException{
        this.retryQueue.put(packet);
        this.pullRequestHoldService.notifyMessageArriving();
    }

    public Packet pull(PullRequest request, boolean isSuspend) {
        long messageCount = pullMessageCount;
        final Packet result = request.getPacket();
        result.setCmd(Command.PULL_RESP.getCmd());
        while(messageCount > 0 && result.getBodyLength() < messageSize * pullMessageCount){
            messageCount--;
            if(!this.poll(result)){
                break;
            }
        }
        if(result.isBodyEmtpy() && isSuspend){
            pullRequestHoldService.suspend(request);
        }
        return result;
    }

    private boolean poll(Packet packet) {
        boolean polled = false;
        Packet one = retryQueue.peek();
        if(one != null){
            retryQueue.poll();
            ByteBuffer buffer = bufferPool.allocate(one.getBody().capacity() + packet.getBody().capacity());
            buffer.put(packet.getBody());
            buffer.put(one.getBody());
            packet.setBody(buffer);
            polled = true;
        } else{
            ConsumerRecord<byte[], byte[]> record = pullQueue.poll();
            if(record != null){
                Header header = new Header(record.topic(), record.partition(), record.offset(),
                        IdService.I.getId(), PullStatus.FOUND.getStatus());
                byte[] headerInBytes = SerializerImpl.getFastJsonSerializer().serialize(header);
                //
                int capacity = 4 + headerInBytes.length + 4 + record.key().length + 4 + record.value().length;
                ByteBuffer buffer = bufferPool.allocate(capacity + packet.getBody().capacity());

                buffer.put(packet.getBody());
                buffer.putInt(headerInBytes.length);
                buffer.put(headerInBytes);
                buffer.putInt(record.key().length);
                buffer.put(record.key());
                buffer.putInt(record.value().length);
                buffer.put(record.value());
                //
                packet.setBody(buffer);
                polled = true;
            }
        }
        return polled;
    }

    public void close(){
        this.pullRequestHoldService.close();
    }
}
