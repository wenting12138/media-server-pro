package com.wenting.mediaserver.config;

public interface IServerBootstrap {

    public void start() throws InterruptedException;

    public void close();

    public void await() throws InterruptedException;
}
