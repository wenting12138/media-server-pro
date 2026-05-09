package com.wenting.mediaserver.bootstrap;

public interface IServerBootstrap {

    public void start() throws InterruptedException;

    public void close();

    public void await() throws InterruptedException;
}
