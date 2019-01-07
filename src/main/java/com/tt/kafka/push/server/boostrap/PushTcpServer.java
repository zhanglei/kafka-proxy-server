package com.tt.kafka.push.server.boostrap;

import com.tt.kafka.client.PushConfigs;
import com.tt.kafka.client.service.*;
import com.tt.kafka.client.transport.Address;
import com.tt.kafka.client.transport.Connection;
import com.tt.kafka.client.transport.codec.PacketDecoder;
import com.tt.kafka.client.transport.codec.PacketEncoder;
import com.tt.kafka.client.transport.handler.MessageDispatcher;
import com.tt.kafka.client.transport.protocol.Command;
import com.tt.kafka.client.transport.protocol.Packet;
import com.tt.kafka.push.server.transport.ClientRegistry;
import com.tt.kafka.push.server.transport.NettyTcpServer;
import com.tt.kafka.push.server.transport.RoundRobinLoadBalance;
import com.tt.kafka.push.server.transport.handler.AckMessageHandler;
import com.tt.kafka.push.server.transport.handler.HeartbeatMessageHandler;
import com.tt.kafka.push.server.transport.handler.ServerHandler;
import com.tt.kafka.push.server.transport.handler.UnregisterMessageHandler;
import com.tt.kafka.util.Constants;
import com.tt.kafka.util.NamedThreadFactory;
import com.tt.kafka.util.NetUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * @Author: Tboy
 */
public class PushTcpServer extends NettyTcpServer {

    private final LoadBalance<Connection> loadBalance;

    private final ChannelHandler handler;

    private final RetryPolicy retryPolicy;

    private final PushConfigs serverConfigs;

    private final RegistryService registryService;

    private final ScheduledThreadPoolExecutor executorService;

    private ScheduledFuture<?> registerScheduledFuture;

    public PushTcpServer(PushConfigs configs) {
        super(configs.getServerPort(), configs.getServerBossNum(), configs.getServerWorkerNum());
        this.serverConfigs = configs;
        this.registryService = new RegistryService(serverConfigs);
        this.loadBalance = new RoundRobinLoadBalance();
        this.handler = new ServerHandler(newDispatcher());
        this.retryPolicy = new DefaultRetryPolicy();
        this.executorService = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("register-zk-thread"));
    }

    private MessageDispatcher newDispatcher(){
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register(Command.HEARTBEAT, new HeartbeatMessageHandler());
        dispatcher.register(Command.UNREGISTER, new UnregisterMessageHandler());
        dispatcher.register(Command.ACK, new AckMessageHandler());
        return dispatcher;
    }

    protected void initTcpOptions(ServerBootstrap bootstrap){
        super.initTcpOptions(bootstrap);
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_SNDBUF, 64 * 1024) //64k
                .option(ChannelOption.SO_RCVBUF, 64 * 1024); //64k
    }

    @Override
    protected void afterStart() {
        doRegister();
        startSchedulerTask();
    }

    private void doRegister(){
        Address address = new Address(NetUtils.getLocalIp(), serverConfigs.getServerPort());
        RegisterMetadata registerMetadata = new RegisterMetadata();
        registerMetadata.setPath(String.format(Constants.ZOOKEEPER_PROVIDERS, serverConfigs.getServerTopic()));
        registerMetadata.setAddress(address);
        this.registryService.register(registerMetadata);
    }

    private void doUnregister(){
        Address address = new Address(NetUtils.getLocalIp(), serverConfigs.getServerPort());
        RegisterMetadata registerMetadata = new RegisterMetadata();
        registerMetadata.setPath(String.format(Constants.ZOOKEEPER_PROVIDERS, serverConfigs.getServerTopic()));
        registerMetadata.setAddress(address);
        this.registryService.unregister(registerMetadata);
    }

    private void startSchedulerTask(){
        registerScheduledFuture = executorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                doRegister();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    protected void initNettyChannel(NioSocketChannel ch) throws Exception{

        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast("encoder", getEncoder());
        //in
        pipeline.addLast("decoder", getDecoder());
        pipeline.addLast("timeOutHandler", new ReadTimeoutHandler(120));
        pipeline.addLast("handler", getChannelHandler());
    }

    public void push(Packet packet, final ChannelFutureListener listener) throws InterruptedException{
        retryPolicy.reset();
        Connection connection = loadBalance.select(ClientRegistry.I.getCopyClients());
        while((connection == null && retryPolicy.allowRetry()) || (!connection.isActive())){
            connection = loadBalance.select(ClientRegistry.I.getCopyClients());
        }
        //
        connection.send(packet, listener);
    }

    @Override
    protected ChannelHandler getEncoder() {
        return new PacketEncoder();
    }

    @Override
    protected ChannelHandler getDecoder() {
        return new PacketDecoder();
    }

    @Override
    protected ChannelHandler getChannelHandler() {
        return handler;
    }

    public void close(){
        super.close();
        this.destroy();
        this.executorService.shutdown();
        this.registryService.close();
    }

    public void destroy(){
        if(registerScheduledFuture != null && !registerScheduledFuture.isDone()){
            registerScheduledFuture.cancel(true);
        }
        this.executorService.purge();
        this.doUnregister();
    }
}