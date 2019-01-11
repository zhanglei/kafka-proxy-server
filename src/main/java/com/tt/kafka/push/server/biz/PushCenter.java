package com.tt.kafka.push.server.biz;

import com.tt.kafka.client.util.SystemPropertiesUtils;
import com.tt.kafka.client.service.DefaultRetryPolicy;
import com.tt.kafka.client.service.IdService;
import com.tt.kafka.client.service.LoadBalance;
import com.tt.kafka.client.service.RetryPolicy;
import com.tt.kafka.client.transport.Connection;
import com.tt.kafka.client.transport.exceptions.ChannelInactiveException;
import com.tt.kafka.client.transport.protocol.Command;
import com.tt.kafka.client.transport.protocol.Header;
import com.tt.kafka.client.transport.protocol.Packet;
import com.tt.kafka.push.server.biz.bo.ControlResult;
import com.tt.kafka.push.server.biz.registry.RegistryCenter;
import com.tt.kafka.push.server.biz.service.*;
import com.tt.kafka.serializer.SerializerImpl;
import com.tt.kafka.util.Constants;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @Author: Tboy
 */
public class PushCenter implements Runnable{

    private static final Logger LOGGER = LoggerFactory.getLogger(PushCenter.class);

    private final int queueSize = SystemPropertiesUtils.getInt(Constants.PUSH_SERVER_QUEUE_SIZE, 100);

    private final LoadBalance<Connection> loadBalance = new RoundRobinLoadBalance();

    private final RetryPolicy retryPolicy = new DefaultRetryPolicy();

    private final LinkedBlockingQueue<Packet> retryQueue = new LinkedBlockingQueue<>(queueSize);

    private final LinkedBlockingQueue<ConsumerRecord<byte[], byte[]>> pushQueue = new LinkedBlockingQueue<>(queueSize);

    private final RepushPolicy repushPolicy = new DefaultFixedTimeRepushPolicy(this);

    private final FlowController flowController = new DefaultFlowController();

    private final AtomicBoolean start = new AtomicBoolean(false);

    private final Thread worker;

    public PushCenter(){
        this.worker = new Thread(this,"push-worker");
        this.worker.setDaemon(true);

        //
        this.start.compareAndSet(false, true);
        this.worker.start();
        this.repushPolicy.start();
    }

    public void push(Packet packet) throws InterruptedException, ChannelInactiveException{
        checkState();
        this.push(packet, new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if(future.isSuccess()){
                    MessageHolder.fastPut(packet);
                } else {
                    retryQueue.put(packet);
                }
            }
        });
    }

    private void push(Packet packet, final ChannelFutureListener listener) throws InterruptedException, ChannelInactiveException {
        ControlResult controlResult = flowController.flowControl(packet);
        while(!controlResult.isAllowed()){
            controlResult = flowController.flowControl(packet);
        }
        retryPolicy.reset();
        Connection connection = loadBalance.select(RegistryCenter.I.getClientRegistry().getClients());
        while((connection == null && retryPolicy.allowRetry()) || (!connection.isWritable() && !connection.isActive())){
            connection = loadBalance.select(RegistryCenter.I.getClientRegistry().getClients());
        }

        //
        connection.send(packet, listener);
    }

    @Override
    public void run() {
        while(this.start.get()){
            Packet ref = null;
            try {
                final Packet packet = take();
                ref = packet;
                push(packet);
            } catch (InterruptedException ex) {
                LOGGER.error("InterruptedException", ex);
            } catch (ChannelInactiveException ex){
                LOGGER.error("ChannelInactiveException", ex);
                if(ref != null){
                    putOrPush(ref);
                }
            }
        }
    }

    private void putOrPush(Packet ref){
        if(Thread.currentThread().getName().startsWith("push-worker")){
            try {
                boolean offer = retryQueue.offer(ref, 50, TimeUnit.MILLISECONDS);
                if(!offer){
                    push(ref);
                }
            } catch (InterruptedException e) {

            } catch (ChannelInactiveException e) {
                putOrPush(ref);
            }
        } else{
            try {
                retryQueue.put(ref);
            } catch (InterruptedException e) {}
        }
    }

    private Packet take() throws InterruptedException{
        Packet packet = retryQueue.peek();
        if(packet != null){
            retryQueue.poll();
        } else{
            ConsumerRecord<byte[], byte[]> record = pushQueue.take();
            packet = new Packet();
            //
            packet.setCmd(Command.PUSH.getCmd());
            packet.setMsgId(IdService.I.getId());
            Header header = new Header(record.topic(), record.partition(), record.offset());
            packet.setHeader(SerializerImpl.getFastJsonSerializer().serialize(header));
            packet.setKey(record.key());
            packet.setValue(record.value());
            //
        }
        return packet;
    }

    public LinkedBlockingQueue<ConsumerRecord<byte[], byte[]>> getPushQueue() {
        return pushQueue;
    }

    private void checkState(){
        if(!start.get()){
            throw new IllegalStateException("push center not start");
        }
    }

    public void close() {
        ((DefaultFixedTimeRepushPolicy) this.repushPolicy).close();
        this.start.compareAndSet(true, false);
        this.worker.interrupt();

    }
}
