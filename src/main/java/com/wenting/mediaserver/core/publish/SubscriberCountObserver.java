package com.wenting.mediaserver.core.publish;

/**
 * Observes derived playback subscriber count transitions.
 */
public interface SubscriberCountObserver {

    void onSubscriberCountChanged(int subscriberCount);
}
